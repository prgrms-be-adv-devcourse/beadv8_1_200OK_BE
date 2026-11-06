package com.ok.refund.application.port.in;

import org.springframework.data.domain.Page;

/**
 * 구매자가 환불을 요청하고 조회·철회하는 입력 포트.
 */
public interface BuyerRefundUseCase {

    /**
     * 주문 상품 정보를 받아 환불 요청을 저장한다.
     *
     * @throws com.ok.common.exception.RestApiException 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                                                  주문 호출이 실패하면 {@code ORDER_API_FAILED}, 그 밖은 환불 서비스의 요청 검증을 따른다
     */
    RefundResult request(RefundRequestCommand command);

    /**
     * 구매자 본인의 환불 요청 목록을 최신순으로 25건씩 조회한다.
     *
     * @param page 0부터 시작하는 페이지 번호
     */
    Page<RefundResult> getBuyerRefunds(Long buyerId, int page);

    /**
     * 구매자가 본인 요청을 승인 전에 거둔다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    RefundResult withdraw(Long refundId, Long buyerId);
}
