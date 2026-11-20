package com.ok.product.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

	@ParameterizedTest
	@MethodSource("내용_없는_본문")
	void 공백_기호_숫자_자모만_있는_본문은_분석할_내용이_없다(String content) {
		// given
		ProductReview sut = ProductReview.create(1L, 1L, 1L, 1L, 1L, 5, content, List.of());

		// when
		boolean analyzable = sut.hasAnalyzableContent();

		// then
		assertThat(analyzable).isFalse();
	}

	static Stream<String> 내용_없는_본문() {
		return Stream.of(" ", "\n\t ", "!!!...", "👍👍", "12345", "ㅋㅋㅋ", "ㅠㅠ", "ㅎㅎ!!", "\u110F\u110F\u110F");
	}

	@ParameterizedTest
	@MethodSource("내용_있는_본문")
	void 자모가_아닌_글자가_하나라도_있으면_분석할_내용이_있다(String content) {
		// given
		ProductReview sut = ProductReview.create(1L, 1L, 1L, 1L, 1L, 5, content, List.of());

		// when
		boolean analyzable = sut.hasAnalyzableContent();

		// then
		assertThat(analyzable).isTrue();
	}

	static Stream<String> 내용_있는_본문() {
		return Stream.of("좋아요", "good", "ㅋㅋ 맛있어요", "굿", "5kg 사과 👍", "\u110F\u1161\u11B8");
	}

	private ProductReview create(List<ProductReviewMediaSpec> mediaSpecs) {
		return ProductReview.create(1L, 1L, 1L, 1L, 1L, 5, "맛있어요", mediaSpecs);
	}
}
