package com.ok.payout.application.port.out;

/**
 * 정산 확정을 Kafka로 보낸다. 같은 애플리케이션 안의 지갑 입금은 {@link PayoutModuleEventPublisher}(Spring 이벤트)가 맡는다.
 * 서비스는 이 인터페이스만 알고, 실제 발행 방식(지금은 Kafka, {@code KafkaPayoutEventPublisher})은 adapter/out/event의 구현체가 맡는다.
 * 나중에 모듈을 서비스로 나눠 발행 방식이 메시지 브로커 등으로 바뀌어도 PayoutService는 바뀌지 않는다.
 *
 * <p>이 패키지(application/port/out)에는 인터페이스만 둔다. 구현체를 여기 두면 ArchitectureTest가 실패한다.
 */
public interface PayoutEventPublisher {

    /**
     * 판매자 몫 정산 확정을 Kafka로 보낸다.
     * 같은 정산이 두 번 갈 수 있어 받는 쪽은 정산 ID(payoutId)로 중복을 막아야 한다.
     */
    void publishSellerPayoutCreated(SellerPayoutCreated created);
}
