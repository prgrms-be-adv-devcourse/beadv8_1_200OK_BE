package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.DeliveryOutboxMessage;
import com.ok.delivery.application.port.in.DeliveryOutboxRelayUseCase;
import com.ok.delivery.application.port.out.DeliveryMessageSender;
import com.ok.delivery.config.DeliveryAsyncConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 배송 outbox의 메시지를 Kafka로 보낸다 (#644). 커밋 직후 줄 하나를 바로 보내는 {@link #send}와,
 * 거기서 못 보낸 줄을 쌓인 순서대로 다시 보내는 {@link #relayPending}(발송 스케줄러)이 있다.
 * 브로커 확인을 기다리는 동안(최대 10초) DB 커넥션을 잡지 않도록 트랜잭션 없이 실행하고,
 * 줄 읽기와 결과 저장은 {@link DeliveryOutboxService}의 짧은 트랜잭션에 맡긴다.
 * {@link #relayPending}은 한 줄이라도 실패하면 그 실행을 거기서 멈춘다(회원 outbox와 같은 정책). 실패는 거의 브로커 장애라 뒤 줄도 기다리다 실패할 뿐이다.
 * 즉시 발송은 줄마다 다른 스레드에서 나가 같은 주문의 메시지 순서를 지키지 않는다. 주문이 순서 뒤바뀜을 처리한다는 전제다(#603).
 * 발송 작업에서 같은 줄이 계속 실패하면 뒤 줄이 모두 기다리므로 {@link #ESCALATE_ATTEMPT_COUNT}번을 넘게 실패하면 ERROR로 남긴다.
 */
@Slf4j
@Component
public class DeliveryOutboxRelayFacade implements DeliveryOutboxRelayUseCase {

    // 즉시 발송 1번과 발송 작업(30초 주기)의 실패를 합친 횟수다. 이만큼 실패하면(약 5분) 잠깐의 장애가 아니라 사람이 봐야 하는 문제로 본다
    static final int ESCALATE_ATTEMPT_COUNT = 10;

    // 원인이 서로를 가리키는 순환이 있어도 끝나도록 따라가는 단계에 상한을 둔다. 실제 사슬은 3~4단계다
    private static final int MAX_CAUSE_DEPTH = 20;

    private final DeliveryOutboxService deliveryOutboxService;
    private final DeliveryMessageSender deliveryMessageSender;
    private final Clock clock;
    private final Duration minAge;
    private final Counter staleDropped;

    // min-age는 발송 작업이 줄을 고르는 기준과 같은 값을 쓴다(DeliveryOutboxRelayScheduler와 같은 키, 같은 기본값)
    public DeliveryOutboxRelayFacade(DeliveryOutboxService deliveryOutboxService,
                                     DeliveryMessageSender deliveryMessageSender,
                                     Clock clock,
                                     @Value("${delivery.outbox.relay.min-age:10s}") Duration minAge,
                                     MeterRegistry meterRegistry) {
        this.deliveryOutboxService = deliveryOutboxService;
        this.deliveryMessageSender = deliveryMessageSender;
        this.clock = clock;
        this.minAge = minAge;
        this.staleDropped = Counter.builder(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_METRIC)
                .description(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_DESCRIPTION)
                .tag("reason", DeliveryAsyncConfig.DROP_REASON_STALE)
                .register(meterRegistry);
    }

    @Override
    public int relayPending(int batchSize, Duration minAge) {
        List<DeliveryOutboxMessage> pending = deliveryOutboxService.findPending(batchSize, minAge);
        int sent = 0;
        for (DeliveryOutboxMessage message : pending) {
            try {
                deliveryMessageSender.send(message.eventType(), message.messageKey(), message.payload());
            } catch (RuntimeException e) {
                logRelayFailure(message, e, pending.size() - sent - 1);
                recordFailure(message, e);
                break;
            }
            markSent(message);
            sent++;
        }
        return sent;
    }

    /**
     * 커밋된 줄 하나를 배송 전용 실행기({@value DeliveryAsyncConfig#OUTBOX_SEND_EXECUTOR})에서 바로 보낸다. 이미 보낸 줄이면 아무것도 하지 않는다.
     * 실패하면 예외를 올리지 않고 실패를 기록해 둔다. 줄은 PENDING으로 남아 {@link #relayPending}이 다시 보낸다.
     *
     * @param recordedAt 줄을 남긴 트랜잭션이 끝난 시각. 대기열에서 오래 기다린 작업을 DB를 읽지 않고 거르는 데 쓴다 (#793)
     * @throws org.springframework.core.task.TaskRejectedException 대기열이 가득 찼거나 실행기가 종료 중이면. 호출한 쪽이 잡아 발송 작업에 맡긴다
     */
    @Async(DeliveryAsyncConfig.OUTBOX_SEND_EXECUTOR)
    public void send(Long outboxId, Instant recordedAt) {
        // 아래는 대기열에서 꺼낸 뒤 발송 스레드에서 돈다.
        // 줄을 남긴 지 min-age가 지났으면 발송 작업이 이미 같은 줄을 집었을 수 있어, 함께 보내 중복을 만들지 않게 발송 작업에 맡긴다 (#793).
        // 줄을 읽기 전에 거르므로 대기열이 밀렸을 때 커넥션도 쓰지 않는다
        if (!Duration.between(recordedAt, clock.instant()).minus(minAge).isNegative()) {
            staleDropped.increment();
            return;
        }
        Optional<DeliveryOutboxMessage> pending = deliveryOutboxService.findPendingById(outboxId);
        if (pending.isEmpty()) {
            return;
        }
        DeliveryOutboxMessage message = pending.get();
        try {
            deliveryMessageSender.send(message.eventType(), message.messageKey(), message.payload());
        } catch (RuntimeException e) {
            log.warn("배송 outbox 즉시 발송 실패, 발송 작업이 다시 보냄. outboxId={}, eventType={}, messageKey={}",
                    message.outboxId(), message.eventType(), message.messageKey(), e);
            recordFailure(message, e);
            return;
        }
        markSent(message);
    }

    // 보낸 뒤 "보냄" 기록이 실패하면 그 줄은 PENDING으로 남아 한 번 더 나간다. 주문은 중복을 무시한다(#603)
    private void markSent(DeliveryOutboxMessage message) {
        if (!deliveryOutboxService.markSent(message.outboxId())) {
            log.info("다른 발송이 먼저 보낸 줄을 한 번 더 보냄, 주문이 중복을 무시. outboxId={}, eventType={}, messageKey={}",
                    message.outboxId(), message.eventType(), message.messageKey());
        }
    }

    private static void logRelayFailure(DeliveryOutboxMessage message, RuntimeException sendFailure, int skipped) {
        int attemptCount = message.attemptCount() + 1;
        if (attemptCount > ESCALATE_ATTEMPT_COUNT) {
            log.error("배송 outbox 발송이 계속 실패해 뒤 줄이 막혀 있음, 확인 필요. outboxId={}, eventType={}, messageKey={}, 시도={}, 남은 줄={}",
                    message.outboxId(), message.eventType(), message.messageKey(), attemptCount, skipped, sendFailure);
        } else {
            log.warn("배송 outbox 발송 실패, 이번 실행을 멈춤. outboxId={}, eventType={}, messageKey={}, 시도={}, 남은 줄={}",
                    message.outboxId(), message.eventType(), message.messageKey(), attemptCount, skipped, sendFailure);
        }
    }

    // 예외를 다시 던지지 않고 줄에 남긴다. 던지면 스케줄러가 30초마다 같은 오류를 찍고, 실패 횟수도 남지 않는다.
    // 기록보다 로그를 먼저 남긴다(호출한 쪽). 기록이 DB 오류로 실패해도 원래 발송 실패 원인이 로그에 남게 하려는 것이다
    private void recordFailure(DeliveryOutboxMessage message, RuntimeException sendFailure) {
        try {
            deliveryOutboxService.recordFailure(message.outboxId(), failureReason(sendFailure));
        } catch (RuntimeException recordFailure) {
            recordFailure.addSuppressed(sendFailure);
            throw recordFailure;
        }
    }

    // 발송기 예외는 어느 줄인지 담고, 실제 이유(브로커 거절, 시간 초과)는 원인 사슬 맨 안쪽에 있어 둘을 함께 남긴다
    static String failureReason(Throwable e) {
        Throwable root = e;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && root.getCause() != null && root.getCause() != root; depth++) {
            root = root.getCause();
        }
        return root == e ? describe(e) : describe(e) + " / " + describe(root);
    }

    private static String describe(Throwable e) {
        String name = e.getClass().getSimpleName();
        return e.getMessage() == null ? name : name + ": " + e.getMessage();
    }
}
