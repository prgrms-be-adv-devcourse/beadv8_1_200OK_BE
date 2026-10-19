package com.ok.common.event;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 주문 결제 금액이 구매자 예치금에서 차감되었음을 알린다. 지갑이 발행하고 주문과 정산이 받는다.
 * 주문은 결제 완료 처리에 쓰고, 정산은 교차 검증용 차감 기록으로 저장한다(정산 리스너: {@code PayoutWalletEventListener}).
 * 같은 주문은 한 번만 차감되므로 지갑은 주문당 한 번 발행한다.
 * 다만 이벤트는 재전달될 수 있으므로, 받는 쪽 처리는 같은 이벤트를 다시 받아도 안전해야 한다.
 * 필드를 바꾸면 주문과 정산 리스너가 함께 영향을 받는다.
 */
public record WalletPaymentCompletedEvent(
        Long orderId,           // 결제한 주문 ID
        Long buyerId,           // 구매자 회원 ID
        Long amount,            // 차감한 금액
        LocalDateTime paidAt    // 차감 시각
) implements DomainEvent {
    public WalletPaymentCompletedEvent {
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(buyerId, "buyerId는 필수입니다.");
        Objects.requireNonNull(amount, "amount는 필수입니다.");
        Objects.requireNonNull(paidAt, "paidAt은 필수입니다.");
    }
}
