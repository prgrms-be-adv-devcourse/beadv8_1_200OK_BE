package com.ok.order.order.application.service;

import com.ok.order.order.application.port.in.OrderOutboxRecordUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 보낼 메시지를 부른 쪽의 트랜잭션 안에서 outbox에 남기고, 커밋되면 바로 보내게 한다 (#666).
 * 평소에는 커밋 직후 나가 지연이 거의 없고, 그때 보내지 못한 줄은 재발송 스케줄러가 다시 보낸다.
 * 커밋 직후 발송 대기열이 가득 차면 기다리지 않고 재발송에 맡긴다(#715).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutboxRecorder implements OrderOutboxRecordUseCase {

    private final OrderOutboxService orderOutboxService;
    private final OrderOutboxRelayFacade orderOutboxRelayFacade;

    @Override
    public void record(String topic, String messageKey, String payload) {
        // 트랜잭션이 없으면 save가 예외를 내므로, 아래 동기화 등록은 항상 트랜잭션 안에서 일어난다
        Long outboxId = orderOutboxService.save(topic, messageKey, payload);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            // 커밋된 뒤에만 보낸다. 롤백되면 줄도 함께 사라져 보낼 것이 없다.
            // 보내기는 다른 스레드에서 한다(relayAfterCommit이 @Async). 부른 요청이 브로커 확인을 기다리지 않게 하려는 것이다
            @Override
            public void afterCommit() {
                try {
                    orderOutboxRelayFacade.relayAfterCommit(outboxId);
                } catch (TaskRejectedException e) {
                    // 이 콜백은 DB 커넥션을 돌려주기 전이라, 자리가 날 때까지 기다리면 커넥션을 쥔 채 멈춘다(#715).
                    // 예외를 올리면 이미 커밋된 주문의 응답이 오류가 되고 같은 커밋의 다른 콜백이 실행되지 않으므로 잡는다.
                    // 줄은 PENDING으로 남아 30초 재발송이 보낸다. 부하가 몰리면 자주 남으므로 스택 없이 한 줄만 남긴다
                    log.warn("[ORDER_OUTBOX_RELAY_SKIPPED] 커밋 직후 발송 대기열이 가득 차 재발송에 맡깁니다. outboxId={}", outboxId);
                }
            }
        });
    }
}
