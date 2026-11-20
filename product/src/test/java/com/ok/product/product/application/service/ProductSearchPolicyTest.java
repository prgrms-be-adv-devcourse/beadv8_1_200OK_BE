package com.ok.product.product.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductSearchPolicyTest {

	@Test
	void 점수_하한이_0이면_만들_수_없다() {
		// when & then
		assertThatThrownBy(() -> new ProductSearchPolicy(0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("product.search.min-score는 0보다 커야 합니다.");
	}
}
