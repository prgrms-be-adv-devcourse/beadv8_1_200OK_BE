package com.ok.wallet.adapter.out.event.dto;

/**
 * {@code wallet.wallet-refund-reverted.v1} 토픽에 보내는 메시지. 필드 이름·타입이 환불과의 약속이다(#611).
 * 돌려준 적 없는 환불도 가져올 돈이 없어 이 메시지로 답한다.
 *
 * @param buyerId 지갑이 실제로 돌려준 구매자. 요청 값과 다르면 환불이 이 값으로 어긋남을 안다
 * @param amount  지갑이 실제로 돌려준 금액(원). 돌려준 적 없는 환불이면 요청 금액
 */
public record WalletRefundRevertedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount
) {
}
