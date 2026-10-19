package com.ok.common.event;

/**
 * 주문 상품의 구매확정을 알린다. 정산은 이 이벤트로 판매자 정산 항목을 만든다.
 *
 * @param orderItemId    주문 항목 ID
 * @param sellerId       판매자 회원 ID
 * @param totalItemPrice 상품 총액(원)
 * @param shippingPrice  이 주문 상품의 배송비(원). 상품 단위로 부과하고 전액 판매자에게 귀속한다. 무료배송이면 0
 */
public record OrderProductConfirmedEvent(
        Long orderItemId,
        Long sellerId,
        Long totalItemPrice,
        Long shippingPrice
) {

}
