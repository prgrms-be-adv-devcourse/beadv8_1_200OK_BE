package com.ok.common.event;

/**
 * 주문 결제 금액을 예치금에서 차감하지 못했음을 알린다. 지갑이 발행하고 주문이 받는다.
 * 주문은 이 이벤트를 받으면 결제 실패로 처리하고 선점한 재고를 푼다.
 *
 * <p>reason은 모듈 간 계약이라 지갑 내부 enum이 아닌 문자열로 둔다.
 * <ul>
 *   <li>INSUFFICIENT_BALANCE: 잔액 부족</li>
 *   <li>INVALID_PAYMENT_REQUEST: 결제 금액이 0 이하 등 처리할 수 없는 요청</li>
 * </ul>
 */
public record WalletPaymentFailedEvent(
        Long orderId,   // 결제하지 못한 주문 ID
        Long buyerId,   // 구매자 회원 ID
        String reason   // 실패 사유 (위 목록)
) implements DomainEvent {
}
