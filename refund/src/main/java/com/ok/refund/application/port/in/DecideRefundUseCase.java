package com.ok.refund.application.port.in;

/**
 * 판매자가 본인에게 온 환불 요청을 승인하거나 거절하는 입력 포트.
 */
public interface DecideRefundUseCase {

    /**
     * 판매자가 본인 상품의 요청을 승인하거나 거절한다.
     * 승인은 같은 묶음의 배송비를 판단해야 해서 주문을 다시 조회하고, 거절은 주문을 부르지 않는다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND},
     *                                                  요청됨이 아니면 {@code INVALID_REFUND_STATE},
     *                                                  승인 때 주문 호출이 실패하면 {@code ORDER_API_FAILED}, 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                                                  거절인데 사유가 비었으면 {@code REJECT_REASON_REQUIRED}
     */
    RefundResult decide(RefundDecisionCommand command);
}
