package com.ok.order.order.adapter.out.event.dto;

/**
 * {@code order.order-refund-reverted.v1} 토픽에 보내는 취소 기록 되돌림. 필드 이름·타입이 환불과의 약속이다(PR #537, 환불 #622).
 * 환불은 이 클래스가 아니라 같은 JSON 모양의 자기 DTO로 읽고, 받으면 환불을 실패로 끝낸다.
 */
public record OrderRefundRevertedMessage(
        Long refundId,
        Long orderId,
        Long orderItemId
) {
}
