package com.ok.product.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductImageUrlValidatorTest {

	private final ProductImageUrlValidator validator = new ProductImageUrlValidator("bucket", "ap-northeast-2");

	@Test
	void 우리_버킷_주소면_허용한다() {
		assertThat(validator.isAllowed("https://bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg")).isTrue();
	}

	@Test
	void 다른_도메인_다른_버킷_다른_리전은_거부한다() {
		assertThat(validator.isAllowed("https://evil.com/a.jpg")).isFalse();
		assertThat(validator.isAllowed("https://other.s3.ap-northeast-2.amazonaws.com/product/a.jpg")).isFalse();
		assertThat(validator.isAllowed("https://bucket.s3.us-east-1.amazonaws.com/product/a.jpg")).isFalse();
	}

	@Test
	void 버킷_이름으로_시작하는_다른_도메인은_거부한다() {
		// 접두사 끝의 '/'까지 비교해서 호스트 뒤에 다른 도메인을 붙인 주소를 막는다
		assertThat(validator.isAllowed("https://bucket.s3.ap-northeast-2.amazonaws.com.evil.com/a.jpg")).isFalse();
	}

	@Test
	void http이거나_null이면_거부한다() {
		assertThat(validator.isAllowed("http://bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg")).isFalse();
		assertThat(validator.isAllowed(null)).isFalse();
	}
}
