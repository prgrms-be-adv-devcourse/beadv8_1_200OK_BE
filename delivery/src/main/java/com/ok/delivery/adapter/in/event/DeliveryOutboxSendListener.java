package com.ok.delivery.adapter.in.event;

import com.ok.delivery.application.dto.DeliveryOutboxRecordedEvent;
import com.ok.delivery.application.port.in.DeliveryOutboxRelayUseCase;
import com.ok.delivery.config.DeliveryAsyncConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;

/**
 * outbox 줄을 남긴 트랜잭션이 커밋되면 그 줄을 바로 Kafka로 보낸다 (#644).
 * 발송 스케줄러만 쓰면 주문이 상태 변경을 최대 30초 늦게 안다. 여기서 못 보낸 줄(실패, 대기열이 가득 차 버림, 커밋 직후 서버 종료)은 PENDING으로 남아 스케줄러가 보낸다.
 * {@code @ApplicationModuleListener}를 쓰지 않는 이유: 이름 없는 {@code @Async}라 모든 리스너가 함께 쓰는 {@code event} 실행기로 가고
 * {@code REQUIRES_NEW} 트랜잭션도 붙는다. 브로커가 느리면 다른 리스너가 밀리고, 브로커 확인을 기다리는 동안 커넥션을 잡는다.
 */
@Component
public class DeliveryOutboxSendListener {

    private final DeliveryOutboxRelayUseCase deliveryOutboxRelayUseCase;
    private final Clock clock;
    private final Duration minAge;
    private final Counter staleDropped;

    // min-age는 발송 작업이 줄을 고르는 기준과 같은 값을 쓴다(DeliveryOutboxRelayScheduler와 같은 키, 같은 기본값)
    public DeliveryOutboxSendListener(DeliveryOutboxRelayUseCase deliveryOutboxRelayUseCase,
                                      Clock clock,
                                      @Value("${delivery.outbox.relay.min-age:10s}") Duration minAge,
                                      MeterRegistry meterRegistry) {
        this.deliveryOutboxRelayUseCase = deliveryOutboxRelayUseCase;
        this.clock = clock;
        this.minAge = minAge;
        this.staleDropped = Counter.builder(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_METRIC)
                .description("배송 outbox 즉시 발송을 하지 않고 발송 작업에 맡긴 건수")
                .tag("reason", DeliveryAsyncConfig.DROP_REASON_STALE)
                .register(meterRegistry);
    }

    // 커밋 뒤 콜백은 요청 스레드에서 돌므로 브로커 확인을 기다리지 않게 전용 실행기로 넘긴다. 아래 본문은 대기열에서 꺼낸 뒤 발송 스레드에서 돈다
    @Async(DeliveryAsyncConfig.OUTBOX_SEND_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(DeliveryOutboxRecordedEvent event) {
        // 줄을 남긴 지 min-age가 지났으면 발송 작업이 이미 같은 줄을 집었을 수 있어, 함께 보내 중복을 만들지 않게 발송 작업에 맡긴다 (#793).
        // 줄을 읽기 전에 거르므로 대기열이 밀렸을 때 커넥션도 쓰지 않는다
        if (!Duration.between(event.recordedAt(), clock.instant()).minus(minAge).isNegative()) {
            staleDropped.increment();
            return;
        }
        deliveryOutboxRelayUseCase.send(event.outboxId());
    }
}
