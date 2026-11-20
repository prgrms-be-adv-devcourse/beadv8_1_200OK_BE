package com.ok.wallet.adapter.out.event.dto;

/**
 * {@code wallet.wallet-refund-revert-failed.v1} 토픽에 보내는 메시지. 필드 이름·타입이 환불과의 약속이다(#611).
 *
 * @param buyerId 지갑이 실제로 돌려준 구매자
 * @param amount  지갑이 실제로 돌려준 금액(원)
 * @param reason  가져오지 못한 사유. {@code INSUFFICIENT_BALANCE}(구매자가 이미 써서 잔액이 모자람)
 */
public record WalletRefundRevertFailedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount,
        String reason
) {
}
