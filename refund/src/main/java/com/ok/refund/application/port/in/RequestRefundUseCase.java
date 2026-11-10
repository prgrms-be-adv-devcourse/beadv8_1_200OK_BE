package com.ok.refund.application.port.in;

/**
 * 구매자가 환불(출고 전 취소)을 요청하는 입력 포트.
 */
public interface RequestRefundUseCase {

    /**
     * 주문 상품 정보를 받아 환불 요청을 저장한다.
     *
     * @throws com.ok.common.exception.RestApiException 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                                                  주문 호출이 실패하면 {@code ORDER_API_FAILED}, 그 밖은 환불 서비스의 요청 검증을 따른다
     */
    RefundResult request(RefundRequestCommand command);
}
