package com.ok.payout.application.port.out;

/**
 * 같은 애플리케이션 안의 다른 모듈(지갑)에 정산 확정을 Spring 이벤트로 알린다.
 * 부른 트랜잭션이 커밋될 때만 전달되고, 롤백되면 전달되지 않는다.
 */
// TODO(#339): 지갑이 Kafka로 받게 되면 이 포트와 구현체(SpringPayoutEventPublisher)를 지운다
public interface PayoutModuleEventPublisher {

    void publishSellerPayoutCreated(SellerPayoutCreated created);
}
