package com.ok.product.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
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
	void 최종_실패_오류는_첫_시도에서_바로_최종_실패가_되고_마지막_오류가_남는다() {
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
	@MethodSource("재시도할_실패_횟수와_대기_시간")
	void 재시도할_실패를_기록할_때마다_다음_시도_시각이_늘어난다(int failures, Duration expectedDelay) {
		// given: 앞선 실패는 각자 다음 시도 시각에 다시 시도해 실패한 것이다
		ReviewAnalysisRequest sut = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);
		for (int i = 1; i < failures; i++) {
			sut.failRetryable("일시 오류 " + i, sut.getNextAttemptAt());
		}
		LocalDateTime failedAt = sut.getNextAttemptAt();

		// when
		sut.failRetryable("일시 오류 " + failures, failedAt);

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
			softly.assertThat(sut.getAttemptCount()).isEqualTo(failures);
			softly.assertThat(sut.getNextAttemptAt()).isEqualTo(failedAt.plus(expectedDelay));
			softly.assertThat(sut.getLastError()).isEqualTo("일시 오류 " + failures);
		});
	}

	static Stream<Arguments> 재시도할_실패_횟수와_대기_시간() {
		return Stream.of(
			Arguments.of(1, Duration.ofMinutes(1)),
			Arguments.of(2, Duration.ofMinutes(5)),
			Arguments.of(3, Duration.ofMinutes(30)),
			Arguments.of(4, Duration.ofHours(2)));
	}

	@Test
	void 재시도할_실패가_5회째면_최종_실패가_되고_마지막_오류가_남는다() {
		// given
		ReviewAnalysisRequest sut = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);
		for (int i = 1; i <= 4; i++) {
			sut.failRetryable("일시 오류 " + i, sut.getNextAttemptAt());
		}

		// when
		sut.failRetryable("일시 오류 5", sut.getNextAttemptAt());

		// then
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getStatus()).isEqualTo(ReviewAnalysisStatus.FAILED);
			softly.assertThat(sut.getAttemptCount()).isEqualTo(5);
			softly.assertThat(sut.getLastError()).isEqualTo("일시 오류 5");
		});
	}

	@ParameterizedTest
	@MethodSource("되돌릴_요청")
	void 끝났거나_백오프_중인_요청을_되돌리면_시도_0회의_대기로_바로_꺼내진다(Consumer<ReviewAnalysisRequest> finish) {
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

	static Stream<Arguments> 되돌릴_요청() {
		return Stream.of(
			Arguments.of((Consumer<ReviewAnalysisRequest>)request -> request.fail("응답을 읽을 수 없음")),
			Arguments.of((Consumer<ReviewAnalysisRequest>)request -> request.failRetryable("일시 오류", NOW)),
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

	@Test
	void 꺼낸_요청은_대기로_남고_다음_시도_시각이_10분_뒤로_미뤄진다() {
		// given
		ReviewAnalysisRequest sut = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);

		// when
		sut.claim(NOW);

		// then
		assertSoftly(softly -> {
			softly.assertThat(sut.getStatus()).isEqualTo(ReviewAnalysisStatus.PENDING);
			softly.assertThat(sut.getAttemptCount()).isZero();
			softly.assertThat(sut.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(10));
		});
	}

	@Test
	void 꺼낸_요청이_재시도할_실패를_받으면_미룬_시각_대신_백오프를_따른다() {
		// given
		ReviewAnalysisRequest sut = ReviewAnalysisRequest.pending(1L, ReviewAnalysisType.SENTIMENT, NOW);
		sut.claim(NOW);

		// when
		sut.failRetryable("일시 오류", NOW);

		// then
		assertThat(sut.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));
	}

	@ParameterizedTest
	@MethodSource("대기가_아닌_요청을_끝내는_동작")
	void 대기가_아닌_요청은_분석_안_함_취소_재시도할_실패로_바꿀_수_없다(Consumer<ReviewAnalysisRequest> finish) {
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
			Arguments.of((Consumer<ReviewAnalysisRequest>)ReviewAnalysisRequest::cancel),
			Arguments.of((Consumer<ReviewAnalysisRequest>)request -> request.failRetryable("늦게 끝난 작업자", NOW)));
	}
}
