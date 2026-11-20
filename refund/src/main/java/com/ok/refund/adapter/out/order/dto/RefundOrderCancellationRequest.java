package com.ok.refund.adapter.out.order.dto;

import com.ok.refund.application.port.out.OrderCancellation;

/**
 * 주문 ② 취소 반영 요청 본문. 필드 이름·타입이 주문과의 약속이다(요청서 ②, 주문 담당 PR #327 댓글로 동의).
 * 이름이 어긋나면 컴파일은 되고 주문 쪽에서 값이 비어 받으므로 어댑터 테스트가 JSON 모양을 확인한다.
 *
 * @param deductedShippingFee 환불 금액에서 뺀 배송비 (원). 빼지 않았으면 0
 */
public record RefundOrderCancellationRequest(
        Long refundId,
        int quantity,
        long deductedShippingFee
) {

    public static RefundOrderCancellationRequest from(OrderCancellation cancellation) {
        return new RefundOrderCancellationRequest(cancellation.refundId(), cancellation.quantity(),
                cancellation.deductedShippingFee());
    }
}
