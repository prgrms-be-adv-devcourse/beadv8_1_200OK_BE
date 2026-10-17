package com.ok.product.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.exception.ProductReviewErrorCode;

class ProductReviewTest {

	private static final ProductReviewMediaSpec IMAGE = new ProductReviewMediaSpec("review/a.jpg", ProductReviewMediaType.IMAGE);
	private static final ProductReviewMediaSpec VIDEO = new ProductReviewMediaSpec("review/a.mp4", ProductReviewMediaType.VIDEO);

	@Test
	void 영상은_1개까지_첨부할_수_있다() {
		// given
		List<ProductReviewMediaSpec> mediaSpecs = List.of(IMAGE, VIDEO);

		// when
		ProductReview sut = create(mediaSpecs);

		// then
		assertThat(sut.getMedia()).hasSize(2);
	}

	@Test
	void 영상이_2개_이상이면_만들_수_없다() {
		// given
		List<ProductReviewMediaSpec> mediaSpecs = List.of(VIDEO, VIDEO);

		// when & then
		assertThatThrownBy(() -> create(mediaSpecs))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.REVIEW_MEDIA_LIMIT_EXCEEDED));
	}

	@Test
	void 영상이_2개_이상이면_수정할_수_없다() {
		// given
		ProductReview review = create(List.of(IMAGE));

		// when & then
		assertThatThrownBy(() -> review.update(5, "좋아요", List.of(VIDEO, VIDEO)))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.REVIEW_MEDIA_LIMIT_EXCEEDED));
	}

	private ProductReview create(List<ProductReviewMediaSpec> mediaSpecs) {
		return ProductReview.create(1L, 1L, 1L, 1L, 1L, 5, "맛있어요", mediaSpecs);
	}
}
