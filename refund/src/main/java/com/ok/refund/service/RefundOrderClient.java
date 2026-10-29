package com.ok.refund.service;

import com.ok.refund.service.dto.RefundableOrderItem;

/**
 * 주문 모듈을 부른다. 1차 구현은 HTTP 동기 호출이고, 주문 팀 회신 전까지 경로·응답 모양은 요청서(설계 9장) 기준 가정이다.
 */
public interface RefundOrderClient {

    /**
     * 환불용 주문 상품 정보를 조회한다. 요청 때와 승인 때 부른다.
     *
     * @throws com.ok.common.exception.RestApiException 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND}, 호출 실패면 {@code ORDER_API_FAILED}
     */
    RefundableOrderItem getOrderItem(Long orderId, Long orderItemId);

    /**
     * 주문에 "N개 취소됨"을 한 번 알린다(흐름 7번). 주문은 환불ID로 중복을 막는다.
     *
     * @throws com.ok.common.exception.RestApiException 호출 실패면 {@code ORDER_API_FAILED}
     */
    void applyRefund(Long refundId, Long orderItemId, int quantity);
}
