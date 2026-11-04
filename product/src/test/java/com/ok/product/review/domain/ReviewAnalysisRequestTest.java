package com.ok.product.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
	@ParameterizedTest
	@MethodSource("끝난_요청")
	void 끝난_요청을_되돌리면_시도_0회의_대기로_바로_꺼내진다(Consumer<ReviewAnalysisRequest> finish) {
		// given
		ReviewAnalysisRequest sut = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);
		finish.accept(sut);
		LocalDateTime editedAt = NOW.plusHours(1);

		// when
		sut.reset(editedAt);

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
			softly.assertThat(sut.getAttemptCount()).isZero();
			softly.assertThat(sut.getNextAttemptAt()).isEqualTo(editedAt);
			softly.assertThat(sut.getLastError()).isNull();
		});
	}

	static Stream<Arguments> 끝난_요청() {
		return Stream.of(
			Arguments.of((Consumer<ReviewAnalysisRequest>)request -> request.fail("응답을 읽을 수 없음")),
			Arguments.of((Consumer<ReviewAnalysisRequest>)ReviewAnalysisRequest::complete),
			Arguments.of((Consumer<ReviewAnalysisRequest>)ReviewAnalysisRequest::skip));
	}

	@Test
	void 내용_없는_리뷰의_요청은_분석_안_함으로_끝난다() {
		// given
		ReviewAnalysisRequest sut = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);

		// when
		sut.skip();

		// then
		assertThat(sut.getStatus()).isEqualTo(ReviewAnalysisStatus.SKIPPED);
	}

	@Test
	void 삭제된_리뷰의_요청은_취소로_끝난다() {
		// given
		ReviewAnalysisRequest sut = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);

		// when
		sut.cancel();

		// then
		assertThat(sut.getStatus()).isEqualTo(ReviewAnalysisStatus.CANCELLED);
	}

	@ParameterizedTest
	@MethodSource("대기가_아닌_요청을_끝내는_동작")
	void 대기가_아닌_요청은_분석_안_함이나_취소로_끝낼_수_없다(Consumer<ReviewAnalysisRequest> finish) {
		// given
		ReviewAnalysisRequest request = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);
		request.complete();

		// when & then
		assertThatThrownBy(() -> finish.accept(request))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductReviewErrorCode.ANALYSIS_REQUEST_NOT_PENDING));
	}

	static Stream<Arguments> 대기가_아닌_요청을_끝내는_동작() {
		return Stream.of(
			Arguments.of((Consumer<ReviewAnalysisRequest>)ReviewAnalysisRequest::skip),
			Arguments.of((Consumer<ReviewAnalysisRequest>)ReviewAnalysisRequest::cancel));
	}
}
