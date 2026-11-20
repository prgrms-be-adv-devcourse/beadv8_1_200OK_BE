package com.ok.refund.application.port.in;

/**
 * 주문이 환불의 취소 기록을 되돌렸다고 알려 준 결과(#622).
 *
 * @param orderItemId 주문이 취소 기록을 되돌린 주문 상품
 */
public record OrderRefundRevertedCommand(
        Long refundId,
        Long orderId,
        Long orderItemId
) {
}
