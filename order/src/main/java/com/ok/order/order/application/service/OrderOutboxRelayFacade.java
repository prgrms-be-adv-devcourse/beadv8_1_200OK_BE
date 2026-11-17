package com.ok.order.order.application.service;

import com.ok.order.order.application.dto.OrderOutboxMessage;
import com.ok.order.order.application.port.in.OrderOutboxRelayUseCase;
import com.ok.order.order.application.port.out.OrderMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 주문 outbox에 쌓인 메시지를 Kafka로 보낸다 (#666).
 * 브로커 확인을 기다리는 동안 DB 커넥션을 잡지 않도록 트랜잭션 없이 실행하고,
 * 줄 읽기와 결과 저장은 {@link OrderOutboxService}의 짧은 트랜잭션에 맡긴다.
 *
 * <p>재발송({@link #relayPending})은 한 줄이라도 실패하면 그 회차를 거기서 멈춘다. 실패는 거의 브로커 장애라 뒤 줄도
 * 줄마다 확인 대기 시간만큼 기다리다 실패할 뿐이고, 멈추면 같은 키의 뒤 줄이 앞 줄보다 먼저 나가지 않는다.
 * 대신 같은 줄이 계속 실패하면 뒤 줄이 모두 기다리므로 {@link #ESCALATE_ATTEMPT_COUNT}번을 넘으면 ERROR로 남긴다(회원 outbox와 같음).
 *
 * <p>커밋 직후 발송({@link #relayAfterCommit})은 방금 남긴 줄 하나만 보낸다. 앞 줄이 실패해 남아 있어도 기다리지 않으므로,
 * 같은 키의 메시지가 짧은 간격으로 이어지면 순서가 뒤집힐 수 있다. 지금 주문 메시지는 같은 키가 몇 분 이상 간격을 두고 나온다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutboxRelayFacade implements OrderOutboxRelayUseCase {

    // 커밋 직후 발송 전용 실행기 이름(#715). 차면 기다리지 않고 거절한다(OrderOutboxRelayExecutorConfig)
    public static final String RELAY_EXECUTOR = "orderOutboxRelayExecutor";

    // 이만큼 실패하면(재발송 30초 주기로 약 5분) 잠깐의 장애가 아니라 사람이 봐야 하는 문제로 본다
    static final int ESCALATE_ATTEMPT_COUNT = 10;

    // 실패 원인을 따라가는 최대 단계. 실제 사슬은 3~4단계라 넉넉히 둔다
    private static final int MAX_CAUSE_DEPTH = 20;

    private final OrderOutboxService orderOutboxService;
    private final OrderMessageSender orderMessageSender;

    @Override
    public int relayPending(int batchSize) {
        List<OrderOutboxMessage> pending = orderOutboxService.findPending(batchSize);
        int sent = 0;
        for (OrderOutboxMessage message : pending) {
            if (!sendAndRecord(message)) {
                log.warn("주문 outbox 재발송을 이번 회차에서 멈춤. 남은 줄={}", pending.size() - sent - 1);
                break;
            }
            sent++;
        }
        return sent;
    }

    /**
     * 커밋 직후 방금 남긴 줄 하나를 전용 실행기에서 보낸다. 실패해도 예외를 던지지 않고 줄에 남겨 재발송에 맡긴다.
     * 그사이 재발송이 먼저 보냈으면 아무것도 하지 않는다.
     *
     * @throws org.springframework.core.task.TaskRejectedException 전용 실행기의 대기열이 가득 차 작업을 받지 못하면.
     *         호출한 쪽이 잡아 재발송에 맡긴다
     */
    @Async(RELAY_EXECUTOR)
    public void relayAfterCommit(Long outboxId) {
        try {
            orderOutboxService.findPendingById(outboxId).ifPresent(this::sendAndRecord);
        } catch (RuntimeException e) {
            // 줄 읽기·기록이 DB 오류로 실패해도 줄은 PENDING으로 남아 재발송이 보낸다
            log.warn("[ORDER_OUTBOX_SEND_FAILED] 커밋 직후 발송을 마치지 못해 재발송에 맡깁니다. outboxId={}", outboxId, e);
        }
    }

    // 보내고 결과를 줄에 남긴다. 보냈으면 true
    private boolean sendAndRecord(OrderOutboxMessage message) {
        try {
            orderMessageSender.send(message.topic(), message.messageKey(), message.payload());
        } catch (RuntimeException e) {
            recordFailure(message, e);
            return false;
        }
        // 보낸 뒤 "보냄" 기록이 실패하면 예외가 올라가고, 줄은 PENDING으로 남아 한 번 더 나간다. 받는 쪽은 업무 키로 중복을 거른다
        orderOutboxService.markSent(message.outboxId());
        return true;
    }

    // 예외를 다시 던지지 않고 줄에 남긴다. 던지면 스케줄러가 주기마다 같은 오류를 찍고, 실패 횟수도 남지 않는다.
    // 줄이 PENDING으로 남아 다음 재발송에서 다시 나가므로 평소에는 WARN이고, 오래 계속되면 ERROR다(service.md 로그 표)
    private void recordFailure(OrderOutboxMessage message, RuntimeException sendFailure) {
        int attemptCount = message.attemptCount() + 1;
        // 기록보다 로그를 먼저 남긴다. 기록이 DB 오류로 실패해도 원래 발송 실패 원인이 로그에 남게 하려는 것이다
        if (attemptCount > ESCALATE_ATTEMPT_COUNT) {
            log.error("[ORDER_OUTBOX_STUCK] 주문 outbox 발송이 계속 실패해 뒤 줄이 막혀 있음, 확인 필요. outboxId={}, topic={}, key={}, 시도={}",
                    message.outboxId(), message.topic(), message.messageKey(), attemptCount, sendFailure);
        } else {
            log.warn("[ORDER_OUTBOX_SEND_FAILED] 주문 outbox 발송 실패. outboxId={}, topic={}, key={}, 시도={}",
                    message.outboxId(), message.topic(), message.messageKey(), attemptCount, sendFailure);
        }
        try {
            orderOutboxService.recordFailure(message.outboxId(), failureReason(sendFailure));
        } catch (RuntimeException recordFailure) {
            // 기록이 실패해도 줄은 PENDING이라 다음 재발송에서 다시 나간다. 위로 올리는 예외에 발송 실패를 붙여 원인을 함께 남긴다
            recordFailure.addSuppressed(sendFailure);
            throw recordFailure;
        }
    }

    // 발송기 예외는 토픽·키를 담고, 실제 이유(브로커 거절, 시간 초과)는 원인 사슬 맨 안쪽에 있다. 둘을 함께 남긴다.
    // 원인이 서로를 가리키는 순환이 있으면 끝없이 돌아 발송이 멈추므로, 따라가는 단계 수에 상한을 둔다
    static String failureReason(Throwable e) {
        Throwable root = e;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && root.getCause() != null && root.getCause() != root; depth++) {
            root = root.getCause();
        }
        return root == e ? describe(e) : describe(e) + " / " + describe(root);
    }

    // 메시지가 없는 예외는 "null"이 아니라 예외 이름만 남긴다. null이라 실패한 것처럼 읽히지 않게 하려는 것이다
    private static String describe(Throwable e) {
        String name = e.getClass().getSimpleName();
        return e.getMessage() == null ? name : name + ": " + e.getMessage();
    }
}
