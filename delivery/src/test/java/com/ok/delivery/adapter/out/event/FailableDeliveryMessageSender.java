package com.ok.delivery.adapter.out.event;

import com.ok.delivery.application.port.out.DeliveryMessageSender;
import com.ok.delivery.domain.DeliveryOutboxEventType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 통합 테스트에서 실제 Kafka 발송기({@link KafkaDeliveryMessageSender})를 감싸, 다음 한 번만 실패시킬 수 있게 하는 발송기다 (#644).
 * 테스트가 함께 쓰는 Kafka 컨테이너는 멈출 수 없어 브로커 장애를 이렇게 흉내 낸다. {@code @MockitoSpyBean}을 쓰면 스프링 컨텍스트가
 * 따로 떠서 느려지므로(testing-strategy.md) 한 번만 등록해 모든 통합 테스트가 같은 컨텍스트를 쓰게 한다(회원 FailableMemberMessageSender와 같음).
 */
@Primary
@Component
public class FailableDeliveryMessageSender implements DeliveryMessageSender {

    private final KafkaDeliveryMessageSender delegate;
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();
    private final AtomicReference<String> lastSendThreadName = new AtomicReference<>();

    FailableDeliveryMessageSender(KafkaDeliveryMessageSender delegate) {
        this.delegate = delegate;
    }

    /**
     * 다음 발송 한 번만 주어진 예외로 실패시킨다. 그다음부터는 실제로 보낸다.
     */
    public void failNextSend(RuntimeException failure) {
        nextFailure.set(failure);
    }

    /**
     * 걸어 둔 실패를 지운다. 테스트가 실패를 쓰지 않고 끝나도 다음 테스트로 넘어가지 않게 한다.
     */
    public void reset() {
        nextFailure.set(null);
        lastSendThreadName.set(null);
    }

    /**
     * 마지막으로 발송을 시도한 스레드 이름. 즉시 발송이 요청 스레드가 아니라 배송 전용 실행기에서 도는지 확인하는 데 쓴다.
     */
    public String lastSendThreadName() {
        return lastSendThreadName.get();
    }

    @Override
    public void send(DeliveryOutboxEventType eventType, String messageKey, String payload) {
        lastSendThreadName.set(Thread.currentThread().getName());
        RuntimeException failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        delegate.send(eventType, messageKey, payload);
    }
}
