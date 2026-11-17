package com.ok.order.order.adapter.in.event.dto;

import java.time.LocalDateTime;

/**
 * 지갑이 {@code wallet.wallet-payment-completed.v1}로 보내는 결제 완료를 주문이 읽는 모양. 필드 이름·타입은 지갑과의 약속이다(#602, 지갑 #640).
 * 지갑 클래스를 import하지 않고 같은 JSON 모양의 주문 DTO로 읽는다.
 *
 * @param amount 차감한 금액(원)
 * @param paidAt 지갑이 차감한 시각(Asia/Seoul). 주문은 결제 완료 시각을 자기 시계로 남겨 지금은 쓰지 않는다
 */
public record WalletPaymentCompletedMessage(
        Long orderId,
        Long buyerId,
        Long amount,
        LocalDateTime paidAt
) {
}
