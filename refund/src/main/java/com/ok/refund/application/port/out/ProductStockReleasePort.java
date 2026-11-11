package com.ok.refund.application.port.out;

/**
 * 상품 모듈에 환불로 취소된 개수만큼 재고를 되돌려 달라고 한다(상품 재고 일부 해제, #363).
 * 서비스는 이 인터페이스만 알고, HTTP로 부르는 방법은 구현체가 맡는다.
 * 상품은 재고를 "주문 + 옵션" 단위로 기억하고 주문 상품 번호를 몰라, 주문에서 받은 옵션 ID를 넘긴다.
 */
public interface ProductStockReleasePort {

    /**
     * 재고 일부 해제를 한 번 요청한다. 같은 refundId로 같은 내용을 다시 보내면 상품은 또 풀지 않고 성공으로 답한다.
     *
     * @throws com.ok.common.exception.RestApiException 상품 모듈이 응답하지 않거나 오류 상태를 돌려주면 {@code PRODUCT_API_FAILED}
     */
    void releaseStock(StockRelease release);
}
