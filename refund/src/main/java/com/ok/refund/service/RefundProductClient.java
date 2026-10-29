package com.ok.refund.service;

/**
 * 상품 모듈을 부른다. 1차 구현은 HTTP 동기 호출이고, 상품 팀 회신 전까지 경로·응답 모양은 요청서(설계 8장) 기준 가정이다.
 */
public interface RefundProductClient {

    /**
     * 주문의 옵션 하나에서 N개만 재고를 되돌린다(흐름 8번). 상품은 환불ID로 중복을 막는다.
     *
     * @throws com.ok.common.exception.RestApiException 호출 실패면 {@code PRODUCT_API_FAILED}
     */
    void releaseStock(Long refundId, Long orderId, Long productOptionId, int quantity);
}
