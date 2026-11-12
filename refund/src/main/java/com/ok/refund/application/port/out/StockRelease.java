package com.ok.refund.application.port.out;

/**
 * 상품에 되돌려 달라고 하는 재고 값. 요청 모양(경로·필드 이름)은 어댑터가 정하고, 서비스는 이 값만 넘긴다.
 *
 * @param refundId        상품이 중복을 가르는 값. 같은 refundId로 같은 내용이 다시 오면 또 풀지 않는다
 * @param orderId         재고를 배정한 주문
 * @param productOptionId 되돌릴 옵션. 주문 상품 조회에서 받아 환불에 저장해 둔 값
 * @param quantity        되돌릴 개수 (환불한 개수)
 */
public record StockRelease(
        Long refundId,
        Long orderId,
        Long productOptionId,
        int quantity
) {
}
