package com.ok.order.order.application.port.in;

import com.ok.order.order.application.dto.RecordOrderItemRefundCommand;

/**
 * 환불 모듈이 승인 직후 알려 준 주문 상품 취소를 기록한다(요청서 ②).
 * 주문 상품 상태는 바꾸지 않고 환불 수량과 뺀 배송비만 남겨, 구매확정 금액에 반영한다.
 */
public interface RecordOrderItemRefundUseCase {

    /**
     * 같은 환불 ID가 같은 내용으로 다시 오면 기록하지 않고 성공으로 끝낸다.
     *
     * @throws com.ok.common.exception.RestApiException 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                                                  같은 환불 ID로 내용이 다르면 {@code REFUND_ID_CONFLICT},
     *                                                  출고 전이 아니면 {@code REFUND_NOT_ALLOWED_STATUS},
     *                                                  환불 수량 합계가 주문 수량을 넘으면 {@code REFUND_QUANTITY_EXCEEDED}
     */
    void recordRefund(RecordOrderItemRefundCommand command);
}
