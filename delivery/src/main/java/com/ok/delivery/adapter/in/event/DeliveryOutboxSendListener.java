package com.ok.delivery.adapter.in.event;

import com.ok.delivery.application.dto.DeliveryOutboxRecordedEvent;
import com.ok.delivery.application.port.in.DeliveryOutboxRelayUseCase;
import com.ok.delivery.config.DeliveryAsyncConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * outbox 줄을 남긴 트랜잭션이 커밋되면 그 줄을 바로 Kafka로 보낸다 (#644).
 * 발송 스케줄러만 쓰면 주문이 상태 변경을 최대 30초 늦게 안다. 여기서 못 보낸 줄(실패, 커밋 직후 서버 종료)은 PENDING으로 남아 스케줄러가 보낸다.
 * {@code @ApplicationModuleListener}를 쓰지 않는 이유: 이름 없는 {@code @Async}라 모든 리스너가 함께 쓰는 {@code event} 실행기로 가고
 * {@code REQUIRES_NEW} 트랜잭션도 붙는다. 브로커가 느리면 다른 리스너가 밀리고, 브로커 확인을 기다리는 동안 커넥션을 잡는다.
 */
@Component
@RequiredArgsConstructor
public class DeliveryOutboxSendListener {

    private final DeliveryOutboxRelayUseCase deliveryOutboxRelayUseCase;

    // 커밋 뒤 콜백은 요청 스레드에서 돌므로 브로커 확인을 기다리지 않게 전용 실행기로 넘긴다
    @Async(DeliveryAsyncConfig.OUTBOX_SEND_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(DeliveryOutboxRecordedEvent event) {
        deliveryOutboxRelayUseCase.send(event.outboxId());
    }
}
