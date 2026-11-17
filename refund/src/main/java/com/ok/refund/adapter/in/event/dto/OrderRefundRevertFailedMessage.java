package com.ok.refund.adapter.in.event.dto;

import com.ok.refund.application.port.in.OrderRefundRevertFailedCommand;

/**
 * 주문이 Kafka로 보내는 취소 기록 되돌리기 실패 메시지를 환불이 읽는 모양. 필드 이름·타입은 PR #537에서 주문과 합의한 약속이다(#622, 주문 #604).
 * 되돌림 메시지 필드에 실패 이유({@code reason}, 예: {@code ALREADY_CONFIRMED})가 더해진다. 값 검사는 리스너가 한다.
 */
public record OrderRefundRevertFailedMessage(
        Long refundId,
        Long orderId,
        Long orderItemId,
        String reason
) {

    public OrderRefundRevertFailedCommand toCommand() {
        return new OrderRefundRevertFailedCommand(refundId, orderId, orderItemId, reason);
    }
}
