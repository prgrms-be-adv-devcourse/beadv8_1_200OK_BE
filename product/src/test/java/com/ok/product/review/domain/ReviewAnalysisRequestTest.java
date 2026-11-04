package com.ok.product.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.exception.ProductReviewErrorCode;

class ReviewAnalysisRequestTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);

	@Test
	void 최종_실패하면_시도_횟수가_늘고_마지막_오류가_남는다() {
		// given
		ReviewAnalysisRequest sut = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);

		// when
		sut.fail("응답을 읽을 수 없음");

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getStatus()).isEqualTo(ReviewAnalysisStatus.FAILED);
			softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
			softly.assertThat(sut.getLastError()).isEqualTo("응답을 읽을 수 없음");
		});
	}

	@Test
	void 대기가_아닌_요청은_다시_끝낼_수_없다() {
		// given
		ReviewAnalysisRequest request = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);
		request.complete();

		// when & then
		assertThatThrownBy(() -> request.fail("늦게 끝난 작업자"))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.ANALYSIS_REQUEST_NOT_PENDING));
	}
}
