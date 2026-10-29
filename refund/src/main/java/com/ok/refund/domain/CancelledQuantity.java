package com.ok.refund.domain;

/**
 * 주문 상품별로 취소된 것으로 세는 개수 합(처리 중 + 완료, 배송 잠금 단계에서 멈춘 건 제외). 묶음 전부 취소 판단에 쓰는 집계 결과다.
 */
public record CancelledQuantity(Long orderItemId, Long quantity) {
}
