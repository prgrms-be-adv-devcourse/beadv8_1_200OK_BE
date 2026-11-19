package com.ok.order.order.application.service;

import com.ok.order.order.application.port.in.OrderOutboxRecordUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;

/**
 * 보낼 메시지를 부른 쪽의 트랜잭션 안에서 outbox에 남기고, 커밋되면 바로 보내게 한다 (#666).
 * 평소에는 커밋 직후 나가 지연이 거의 없고, 그때 보내지 못한 줄은 재발송 스케줄러가 다시 보낸다.
 * 커밋 직후 발송 대기열이 가득 차면 기다리지 않고 재발송에 맡기고 건수만 센다(#715, #794).
 */
@Component
public class OrderOutboxRecorder implements OrderOutboxRecordUseCase {

    private final OrderOutboxService orderOutboxService;
    private final OrderOutboxRelayFacade orderOutboxRelayFacade;
    private final Clock clock;
    private final Counter rejectedDropped;

    public OrderOutboxRecorder(OrderOutboxService orderOutboxService,
                               OrderOutboxRelayFacade orderOutboxRelayFacade,
                               Clock clock,
                               MeterRegistry meterRegistry) {
        this.orderOutboxService = orderOutboxService;
        this.orderOutboxRelayFacade = orderOutboxRelayFacade;
        this.clock = clock;
        this.rejectedDropped = Counter.builder(OrderOutboxRelayFacade.SEND_DROPPED_METRIC)
                .description("주문 outbox 커밋 직후 발송을 하지 않고 재발송에 맡긴 건수")
                .tag("reason", OrderOutboxRelayFacade.DROP_REASON_REJECTED)
                .register(meterRegistry);
    }

    @Override
    public void record(String topic, String messageKey, String payload) {
        // 트랜잭션이 없으면 save가 예외를 내므로, 아래 동기화 등록은 항상 트랜잭션 안에서 일어난다
        Long outboxId = orderOutboxService.save(topic, messageKey, payload);
        // 대기열에서 기다린 시간을 재는 발송 쪽과 같은 Clock으로 시각을 남긴다. createdAt은 JPA 감사가 Clock 없이 채운다
        Instant recordedAt = clock.instant();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            // 커밋된 뒤에만 보낸다. 롤백되면 줄도 함께 사라져 보낼 것이 없다.
            // 보내기는 다른 스레드에서 한다(relayAfterCommit이 @Async). 부른 요청이 브로커 확인을 기다리지 않게 하려는 것이다
            @Override
            public void afterCommit() {
                try {
                    orderOutboxRelayFacade.relayAfterCommit(outboxId, recordedAt);
                } catch (TaskRejectedException e) {
                    // 이 콜백은 DB 커넥션을 돌려주기 전이라, 자리가 날 때까지 기다리면 커넥션을 쥔 채 멈춘다(#715).
                    // 예외를 올리면 이미 커밋된 주문의 응답이 오류가 되고 같은 커밋의 다른 콜백이 실행되지 않으므로 잡는다.
                    // 줄은 PENDING으로 남아 30초 재발송이 보낸다. 로그 대신 건수만 센다. 몰릴 때 건마다 로그를 쓰면 그만큼 커넥션을 오래 잡는다(#794)
                    rejectedDropped.increment();
                }
            }
        });
    }
}
