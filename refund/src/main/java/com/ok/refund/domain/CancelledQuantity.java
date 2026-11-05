package com.ok.refund.domain;

/**
 * 같은 판매자 묶음에서 이미 취소가 진행 중인 개수를 주문 상품별로 모은 값.
 * 저장소 집계 쿼리가 만들어 배송비 판단에 넘긴다.
 *
 * @param quantity 처리 중인 환불 요청들의 취소 개수 합 (JPQL {@code sum}이 {@code Long}을 돌려줘 {@code long}으로 받는다)
 */
public record CancelledQuantity(Long orderItemId, long quantity) {
}
