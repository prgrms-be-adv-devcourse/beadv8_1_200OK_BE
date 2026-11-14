package com.ok.wallet.adapter.out.event.dto;

/**
 * {@code wallet.wallet-refund-failed} 토픽에 보내는 메시지. 필드 이름·타입이 환불과의 약속이다(#34, 환불 PR #555).
 * 환불은 사유가 비었거나 100자를 넘으면 받지 않는다.
 *
 * @param amount 환불이 요청한 금액 그대로(원). 환불은 이 값을 자기 금액과 비교한다
 * @param reason 거절 사유. {@code PAYMENT_NOT_FOUND}(결제 기록 없음), {@code REFUND_TOTAL_EXCEEDED}(반환 합계가 결제 금액 초과)
 */
public record WalletRefundFailedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount,
        String reason
) {
}
