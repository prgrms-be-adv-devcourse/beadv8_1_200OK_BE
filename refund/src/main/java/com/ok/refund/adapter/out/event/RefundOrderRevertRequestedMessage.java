package com.ok.refund.adapter.out.event;

import com.ok.refund.application.port.out.OrderRevertRequest;

/**
 * {@link RefundTopics#REFUND_ORDER_REVERT_REQUESTED} 토픽에 보내는 메시지. 이 필드 이름·타입이 주문과의 약속이다(PR #537, 주문 #604).
 * 주문은 이 클래스가 아니라 JSON 모양으로 읽는다(타입 헤더를 싣지 않음). 필드를 바꾸면 주문 쪽도 같이 바꿔야 한다.
 *
 * @param refundId 주문이 되돌릴 취소 기록을 찾고 중복을 가르는 값. 같은 refundId가 다시 오면 한 번만 되돌린다(주문 쪽 약속)
 * @param quantity 되돌릴 취소 수량 (1 이상)
 */
public record RefundOrderRevertRequestedMessage(
        Long refundId,
        Long orderId,
        Long orderItemId,
        int quantity
) {

    static RefundOrderRevertRequestedMessage from(OrderRevertRequest request) {
        return new RefundOrderRevertRequestedMessage(request.refundId(), request.orderId(), request.orderItemId(), request.quantity());
    }
}
