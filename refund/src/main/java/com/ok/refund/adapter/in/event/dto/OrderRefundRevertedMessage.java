package com.ok.refund.adapter.in.event.dto;

import com.ok.refund.application.port.in.OrderRefundRevertedCommand;

/**
 * 주문이 Kafka로 보내는 취소 기록 되돌림 메시지를 환불이 읽는 모양. 필드 이름·타입은 PR #537에서 주문과 합의한 약속이다(#622, 주문 #604).
 * 값 검사는 리스너가 한다.
 */
public record OrderRefundRevertedMessage(
        Long refundId,
        Long orderId,
        Long orderItemId
) {

    public OrderRefundRevertedCommand toCommand() {
        return new OrderRefundRevertedCommand(refundId, orderId, orderItemId);
    }
}
