package com.ok.product.product.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 구매자 상품 상세의 품절 임박 기준({@code product.low-stock-threshold})을 가진다.
 * 옵션의 가용 재고가 이 값 이하이면 품절 임박이다. 재고 수량은 공개하지 않고 임박 여부만 내려 주므로 기준은 서버가 정한다(#814).
 */
@Component
class ProductLowStockPolicy {

	private final int threshold;

	ProductLowStockPolicy(@Value("${product.low-stock-threshold}") int threshold) {
		if (threshold < 0) {
			throw new IllegalArgumentException("product.low-stock-threshold는 0 이상이어야 합니다.");
		}
		this.threshold = threshold;
	}

	public int threshold() {
		return threshold;
	}
}
