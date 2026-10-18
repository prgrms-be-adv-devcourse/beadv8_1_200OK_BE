package com.ok.common.event;

import java.time.LocalDateTime;

/**
 * 주문 결제 금액이 구매자 예치금에서 차감되었음을 알린다. 지갑이 발행하고 주문이 받는다.
 * 같은 주문은 한 번만 차감되므로 orderId당 한 번 발행된다.
 */
public record WalletPaymentCompletedEvent(
        Long orderId,           // 결제한 주문 ID
        Long buyerId,           // 구매자 회원 ID
        Long amount,            // 차감한 금액
        LocalDateTime paidAt    // 차감 시각
) implements DomainEvent {
}
