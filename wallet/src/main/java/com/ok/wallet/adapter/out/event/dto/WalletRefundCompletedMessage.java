package com.ok.wallet.adapter.out.event.dto;

/**
 * {@code wallet.wallet-refund-completed} 토픽에 보내는 메시지. 필드 이름·타입이 환불과의 약속이다(#34, 환불 PR #555).
 * 환불은 이 클래스가 아니라 JSON 모양으로 읽는다(타입 헤더를 싣지 않음). 필드를 바꾸면 환불 쪽도 같이 바꿔야 한다.
 *
 * @param amount 돌려준 금액(원). 같은 환불이 다시 와도 처음 돌려준 금액이다
 */
public record WalletRefundCompletedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount
) {
}
