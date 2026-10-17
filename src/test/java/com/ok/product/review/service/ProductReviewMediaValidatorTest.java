package com.ok.product.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.product.review.domain.ProductReviewMediaSpec;
import com.ok.product.review.domain.ProductReviewMediaType;
import com.ok.product.review.exception.ProductReviewErrorCode;

class ProductReviewMediaValidatorTest {

	private static final String BASE_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/";

	private final ProductReviewMediaValidator sut =
		new ProductReviewMediaValidator(new MediaUrlResolver("bucket", "ap-northeast-2"));

	@Test
	void jpg는_이미지로_objectKey를_꺼낸다() {
		// when
		ProductReviewMediaSpec spec = sut.toSpec(BASE_URL + "product_review/3f2b8c1e-9a4d-4e6f-b7a1-0c2d3e4f5a6b.jpg");

		// then
		assertThat(spec.objectKey()).isEqualTo("product_review/3f2b8c1e-9a4d-4e6f-b7a1-0c2d3e4f5a6b.jpg");
		assertThat(spec.type()).isEqualTo(ProductReviewMediaType.IMAGE);
	}

	@Test
	void mp4는_영상으로_정한다() {
		// when
		ProductReviewMediaSpec spec = sut.toSpec(BASE_URL + "product_review/a.mp4");

		// then
		assertThat(spec.type()).isEqualTo(ProductReviewMediaType.VIDEO);
	}

	@ParameterizedTest
	@MethodSource("허용하지_않는_URL")
	void 리뷰_첨부_경로가_아니면_거부한다(String url) {
		// when & then
		assertThatThrownBy(() -> sut.toSpec(url))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.INVALID_REVIEW_MEDIA_URL));
	}

	static Stream<String> 허용하지_않는_URL() {
		return Stream.of(
			"https://evil.com/product_review/a.jpg",
			"https://other.s3.ap-northeast-2.amazonaws.com/product_review/a.jpg",
			"https://bucket.s3.ap-northeast-2.amazonaws.com.evil.com/product_review/a.jpg",
			"http://bucket.s3.ap-northeast-2.amazonaws.com/product_review/a.jpg",
			BASE_URL + "product/a.jpg",
			BASE_URL + "product_review/../product/a.jpg",
			BASE_URL + "product_review/%2e%2e/product/a.jpg",
			BASE_URL + "product_review/sub/a.jpg",
			BASE_URL + "product_review/a.png",
			BASE_URL + "product_review/a.jpg?x=1",
			BASE_URL + "product_review/a",
			BASE_URL + "product_review/",
			null);
	}
}
