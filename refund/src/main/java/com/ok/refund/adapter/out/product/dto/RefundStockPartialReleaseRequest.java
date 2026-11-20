package com.ok.refund.adapter.out.product.dto;

import com.ok.refund.application.port.out.StockRelease;

/**
 * 상품 재고 일부 해제 요청 본문. 필드 이름·타입이 상품과의 약속이다(상품 {@code StockPartialReleaseRequest}, #363).
 * 이름이 어긋나면 컴파일은 되고 상품 쪽에서 값이 비어 400이 나므로 어댑터 테스트가 JSON 모양을 확인한다.
 * 주문 ID는 경로로 보내 본문에는 없다.
 */
public record RefundStockPartialReleaseRequest(
        Long refundId,
        Long productOptionId,
        int quantity
) {

    public static RefundStockPartialReleaseRequest from(StockRelease release) {
        return new RefundStockPartialReleaseRequest(release.refundId(), release.productOptionId(), release.quantity());
    }
}
