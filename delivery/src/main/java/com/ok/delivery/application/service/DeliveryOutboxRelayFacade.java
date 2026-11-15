package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.DeliveryOutboxMessage;
import com.ok.delivery.application.port.in.DeliveryOutboxRelayUseCase;
import com.ok.delivery.application.port.out.DeliveryMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 배송 outbox에 쌓인 메시지를 쌓인 순서대로 보낸다 (#644).
 * 브로커 확인을 기다리는 동안(최대 10초) DB 커넥션을 잡지 않도록 트랜잭션 없이 실행하고,
 * 줄 읽기와 결과 저장은 {@link DeliveryOutboxService}의 짧은 트랜잭션에 맡긴다.
 * 한 줄이라도 실패하면 그 실행은 거기서 멈춘다(회원 outbox와 같은 정책). 실패는 거의 브로커 장애라 뒤 줄도 기다리다 실패할 뿐이고,
 * 멈추면 같은 주문의 배송 완료가 배송 시작보다 먼저 나가는 일이 없다.
 * 대신 같은 줄이 계속 실패하면 뒤 줄이 모두 기다리므로 {@link #ESCALATE_ATTEMPT_COUNT}번을 넘게 실패하면 ERROR로 남겨 눈에 띄게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryOutboxRelayFacade implements DeliveryOutboxRelayUseCase {

    // 이만큼 실패하면(30초 주기로 약 5분) 잠깐의 장애가 아니라 사람이 봐야 하는 문제로 본다
    static final int ESCALATE_ATTEMPT_COUNT = 10;

    // 실패 원인을 따라가는 최대 단계. 실제 사슬은 3~4단계라 넉넉히 둔다
    private static final int MAX_CAUSE_DEPTH = 20;

    private final DeliveryOutboxService deliveryOutboxService;
    private final DeliveryMessageSender deliveryMessageSender;

    @Override
    public int relayPending(int batchSize) {
        List<DeliveryOutboxMessage> pending = deliveryOutboxService.findPending(batchSize);
        int sent = 0;
        for (DeliveryOutboxMessage message : pending) {
            try {
                deliveryMessageSender.send(message.eventType(), message.messageKey(), message.payload());
            } catch (RuntimeException e) {
                handleFailure(message, e, pending.size() - sent - 1);
                break;
            }
            // 보낸 뒤 "보냄" 기록이 실패하면 예외가 올라가 실행이 멈추고, 그 줄은 PENDING으로 남아 다음 실행에서 한 번 더 나간다.
            // 받는 쪽(주문)은 같은 메시지를 두 번 받아도 한 번만 반영한다(#603 중복 무시)
            deliveryOutboxService.markSent(message.outboxId());
            sent++;
        }
        return sent;
    }

    // 예외를 다시 던지지 않고 줄에 남긴다. 던지면 스케줄러가 30초마다 같은 오류를 찍고, 실패 횟수도 남지 않는다.
    // 줄이 PENDING으로 남아 다음 실행에서 다시 나가므로 평소에는 WARN이고, 오래 계속되면 ERROR다(service.md 로그 표)
    private void handleFailure(DeliveryOutboxMessage message, RuntimeException sendFailure, int skipped) {
        int attemptCount = message.attemptCount() + 1;
        // 기록보다 로그를 먼저 남긴다. 기록이 DB 오류로 실패해도 원래 발송 실패 원인이 로그에 남게 하려는 것이다
        if (attemptCount > ESCALATE_ATTEMPT_COUNT) {
            log.error("배송 outbox 발송이 계속 실패해 뒤 줄이 막혀 있음, 확인 필요. outboxId={}, eventType={}, messageKey={}, 시도={}, 남은 줄={}",
                    message.outboxId(), message.eventType(), message.messageKey(), attemptCount, skipped, sendFailure);
        } else {
            log.warn("배송 outbox 발송 실패, 이번 실행을 멈춤. outboxId={}, eventType={}, messageKey={}, 시도={}, 남은 줄={}",
                    message.outboxId(), message.eventType(), message.messageKey(), attemptCount, skipped, sendFailure);
        }
        try {
            deliveryOutboxService.recordFailure(message.outboxId(), failureReason(sendFailure));
        } catch (RuntimeException recordFailure) {
            // 기록이 실패해도 줄은 PENDING이라 다음 실행에서 다시 나간다. 위로 올리는 예외에 발송 실패를 붙여 원인을 함께 남긴다
            recordFailure.addSuppressed(sendFailure);
            throw recordFailure;
        }
    }

    // 발송기 예외는 어느 줄인지 담고, 실제 이유(브로커 거절, 시간 초과)는 원인 사슬 맨 안쪽에 있다. 둘을 함께 남긴다.
    // 원인이 서로를 가리키는 순환이 있으면 끝없이 돌아 발송 스케줄러가 멈추므로, 따라가는 단계 수에 상한을 둔다
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
