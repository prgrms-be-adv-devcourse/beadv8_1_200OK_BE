package com.ok.order.order.adapter.out.event.dto;

/**
 * {@code order.order-refund-revert-failed.v1} 토픽에 보내는 취소 기록 되돌리기 실패. 필드 이름·타입이 환불과의 약속이다(PR #537, 환불 #622).
 * 환불은 받으면 환불을 처리 중으로 남겨 사람이 보게 한다.
 *
 * @param reason 되돌리지 못한 이유(예: {@code ALREADY_CONFIRMED}, {@code ALREADY_SHIPPED}). 환불이 100자 넘으면 거절하므로 짧은 코드만 쓴다
 */
public record OrderRefundRevertFailedMessage(
        Long refundId,
        Long orderId,
        Long orderItemId,
        String reason
) {
}
