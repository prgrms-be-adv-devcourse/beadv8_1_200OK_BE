package com.ok.product.review.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewSentimentCriteriaTest {

	@Test
	void product_리소스의_분류_기준을_읽어_버전을_돌려준다() {
		// when
		ReviewSentimentCriteria sut = new ReviewSentimentCriteria();

		// then
		assertThat(sut.version()).isEqualTo("v0.2");
	}
}
