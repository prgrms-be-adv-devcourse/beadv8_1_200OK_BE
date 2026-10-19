package com.ok.payout.service.publisher;

import com.ok.payout.service.dto.SellerPayoutCreatedCommand;

/**
 * 정산에서 일어난 일을 다른 모듈(지갑)에 알린다.
 * 서비스는 이 인터페이스만 알고, 실제 발행 방식(지금은 Spring 이벤트)은 event 패키지의 구현체가 맡는다.
 * 나중에 모듈을 서비스로 나눠 발행 방식이 메시지 브로커 등으로 바뀌어도 PayoutService는 바뀌지 않는다.
 *
 * <p>이 패키지(service/publisher)에는 인터페이스만 둔다. 구현체를 여기 두면 ArchitectureTest가 실패한다.
 */
public interface PayoutEventPublisher {

    /**
     * 판매자 몫 정산이 확정되었음을 지갑에 알린다.
     * 지갑은 정산 ID(payoutId)로 같은 정산이 두 번 와도 한 번만 반영한다.
     */
    void publishSellerPayoutCreated(SellerPayoutCreatedCommand command);
}
