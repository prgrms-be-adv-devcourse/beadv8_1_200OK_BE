package com.ok.order.order.adapter.in.event.dto;

/**
 * 환불이 {@code refund.refund-order-revert-requested.v1}로 보내는 취소 기록 되돌리기 요청을 주문이 읽는 모양. 필드 이름·타입은 환불과의 약속이다(PR #537, 환불 #622).
 * 환불 클래스를 import하지 않고 같은 JSON 모양의 주문 DTO로 읽는다. 값이 빠졌는지 알 수 있게 수량도 {@code Integer}로 받는다.
 *
 * @param quantity 되돌릴 취소 수량(1 이상)
 */
public record RefundOrderRevertRequestedMessage(
        Long refundId,
        Long orderId,
        Long orderItemId,
        Integer quantity
) {
}
