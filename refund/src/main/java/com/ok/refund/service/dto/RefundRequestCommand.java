package com.ok.refund.service.dto;

/**
 * 구매자의 환불 요청.
 *
 * @param buyerId  로그인 토큰에서 꺼낸 구매자 ID (본문으로 받지 않는다)
 * @param quantity 취소할 개수
 */
public record RefundRequestCommand(Long buyerId, Long orderId, Long orderItemId, int quantity) {
}
