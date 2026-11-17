package com.ok.refund.application.port.in;

/**
 * 주문이 환불의 취소 기록을 되돌리지 못했다고 알려 준 결과(#622).
 *
 * @param reason 주문이 알려 준 실패 이유(예: 이미 구매확정 {@code ALREADY_CONFIRMED}). 되돌리기 줄의 이유(last_reason)에 그대로 남긴다
 */
public record OrderRefundRevertFailedCommand(
        Long refundId,
        Long orderId,
        Long orderItemId,
        String reason
) {
}
