package com.ok.wallet.adapter.out.event.dto;

import java.time.LocalDateTime;

/**
 * {@code wallet.wallet-payment-completed.v1} 토픽에 보내는 메시지. 필드 이름·타입이 주문(#602)·정산(#339)과의 약속이다.
 * 받는 쪽은 이 클래스가 아니라 JSON 모양으로 읽는다(타입 헤더를 싣지 않음). 필드를 바꾸면 주문·정산도 같이 바꿔야 한다.
 *
 * @param amount 차감한 금액(원)
 * @param paidAt 지갑이 차감한 시각. 오프셋 없는 ISO-8601(Asia/Seoul)로 나간다
 */
public record WalletPaymentCompletedMessage(
        Long orderId,
        Long buyerId,
        long amount,
        LocalDateTime paidAt
) {
}
