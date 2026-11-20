package com.ok.common.event;

import java.util.Objects;

/**
 * 주문 결제 금액을 예치금에서 차감하지 못했음을 알린다. 지갑이 발행하고 주문이 받는다.
 * 주문은 이 이벤트를 받으면 결제 실패로 처리하고 선점한 재고를 푼다.
 * 이벤트는 재전달될 수 있으므로, 주문 쪽 처리는 같은 이벤트를 다시 받아도 안전해야 한다.
 *
 * <p>실패 사유는 아래 상수 중 하나다. common.event에는 DomainEvent만 둘 수 있어 enum 대신 문자열 상수로 둔다.
 * 주문은 사유와 관계없이 결제 실패로 처리하고, 모르는 값을 받아도 실패로 처리한다.
 */
public record WalletPaymentFailedEvent(
        Long orderId,   // 결제하지 못한 주문 ID
        Long buyerId,   // 구매자 회원 ID
        String reason   // 실패 사유 (아래 상수)
) implements DomainEvent {
    /** 잔액 부족 */
    public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    /** 결제 금액이 0 이하 등 처리할 수 없는 요청 */
    public static final String INVALID_PAYMENT_REQUEST = "INVALID_PAYMENT_REQUEST";

    public WalletPaymentFailedEvent {
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(buyerId, "buyerId는 필수입니다.");
        Objects.requireNonNull(reason, "reason은 필수입니다.");
    }
}
