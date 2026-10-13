package com.ok.product.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
	void 상품_이미지_경로가_아니면_거부한다() {
		assertThat(validator.isAllowed("https://bucket.s3.ap-northeast-2.amazonaws.com/member_profile/a.jpg")).isFalse();
		assertThat(validator.isAllowed("https://bucket.s3.ap-northeast-2.amazonaws.com/a.jpg")).isFalse();
	}

	@Test
	void 업로드_키_형식의_파일명이면_허용한다() {
		assertThat(validator.isAllowed(
			"https://bucket.s3.ap-northeast-2.amazonaws.com/product/3f2b8c1e-9a4d-4e6f-b7a1-0c2d3e4f5a6b.jpg")).isTrue();
	}

	@ParameterizedTest
	@MethodSource("상품_이미지_경로를_벗어날_수_있는_경로")
	void 상품_이미지_경로를_벗어날_수_있는_주소는_거부한다(String path) {
		assertThat(validator.isAllowed("https://bucket.s3.ap-northeast-2.amazonaws.com/product/" + path)).isFalse();
	}

	static Stream<String> 상품_이미지_경로를_벗어날_수_있는_경로() {
		return Stream.of(
			"../member_profile/a.jpg",
			"%2e%2e/member_profile/a.jpg",
			"%2E%2E/member_profile/a.jpg",
			".%2e/member_profile/a.jpg",
			"..%5cmember_profile/a.jpg",
			"..\\member_profile\\a.jpg",
			"sub/a.jpg",
			"a.jpg?x=1",
			"a.jpg#x",
			"",
			"a");
	}

	@Test
	void http이거나_null이면_거부한다() {
		assertThat(validator.isAllowed("http://bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg")).isFalse();
		assertThat(validator.isAllowed(null)).isFalse();
	}
}
