package com.ok.common.event;

import java.util.Objects;

/**
 * 주문 결제 금액을 예치금에서 차감하지 못했음을 알린다. 지갑이 발행하고 주문이 받는다.
 * 주문은 이 이벤트를 받으면 결제 실패로 처리하고 선점한 재고를 푼다.
 * 이벤트는 재전달될 수 있으므로, 주문 쪽 처리는 같은 이벤트를 다시 받아도 안전해야 한다.
 */
public record WalletPaymentFailedEvent(
        Long orderId,   // 결제하지 못한 주문 ID
        Long buyerId,   // 구매자 회원 ID
        Reason reason   // 실패 사유
) implements DomainEvent {
    /** 결제 실패 사유. 이 이벤트의 계약이며, 주문은 모르는 값을 받아도 결제 실패로 처리한다. */
    public enum Reason {
        INSUFFICIENT_BALANCE,     // 잔액 부족
        INVALID_PAYMENT_REQUEST   // 결제 금액이 0 이하 등 처리할 수 없는 요청
    }

    public WalletPaymentFailedEvent {
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(buyerId, "buyerId는 필수입니다.");
        Objects.requireNonNull(reason, "reason은 필수입니다.");
    }
}
