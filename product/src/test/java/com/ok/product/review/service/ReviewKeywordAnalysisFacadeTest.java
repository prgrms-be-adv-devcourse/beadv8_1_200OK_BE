package com.ok.product.review.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewAnalyzerUnavailableException;
import com.ok.product.review.client.ReviewKeywordAnalyzer;
import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordInput;
import com.ok.product.review.service.dto.ReviewKeywordTarget;

@ExtendWith(MockitoExtension.class)
class ReviewKeywordAnalysisFacadeTest {

	private static final ReviewKeywordTarget FIRST = target(1L);
	private static final ReviewKeywordTarget SECOND = target(2L);

	@Mock
	ReviewKeywordService reviewKeywordService;

	@Mock
	ReviewKeywordAnalyzer reviewKeywordAnalyzer;

	MutableClock clock;
	ReviewKeywordAnalysisFacade sut;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(Instant.parse("2026-11-05T00:00:00Z"));
		sut = new ReviewKeywordAnalysisFacade(reviewKeywordService, reviewKeywordAnalyzer, clock);
	}

	@Test
	void 분석기를_쓸_수_없으면_남은_요청을_분석하지_않고_실패로_기록하지_않는다() {
		// given
		given(reviewKeywordService.findPendingTargets(anyInt())).willReturn(List.of(FIRST, SECOND));
		given(reviewKeywordAnalyzer.analyze(FIRST.input())).willThrow(unavailable());

		// when
		sut.analyzePending();

		// then
		then(reviewKeywordAnalyzer).should(never()).analyze(SECOND.input());
		then(reviewKeywordService).should(never()).fail(any(), any(), anyBoolean());
		then(reviewKeywordService).should(never()).complete(any(), any());
	}

	@Test
	void 분석기를_쓸_수_없으면_5분_동안_요청을_꺼내지_않는다() {
		// given
		given(reviewKeywordService.findPendingTargets(anyInt())).willReturn(List.of(FIRST));
		given(reviewKeywordAnalyzer.analyze(FIRST.input())).willThrow(unavailable());
		sut.analyzePending();
		clock.advance(Duration.ofMinutes(5).minusSeconds(1));

		// when
		sut.analyzePending();

		// then
		then(reviewKeywordService).should(times(1)).findPendingTargets(anyInt());
	}

	@Test
	void 쉬는_시간_5분이_지나면_다시_요청을_분석한다() {
		// given
		given(reviewKeywordService.findPendingTargets(anyInt())).willReturn(List.of(FIRST));
		given(reviewKeywordAnalyzer.analyze(FIRST.input())).willThrow(unavailable()).willReturn(analysis());
		sut.analyzePending();
		clock.advance(Duration.ofMinutes(5));

		// when
		sut.analyzePending();

		// then
		then(reviewKeywordService).should().complete(FIRST, analysis());
	}

	@Test
	void 분석_실패는_실패로_기록하고_쉬지_않는다() {
		// given
		ReviewAnalysisFailedException failure = ReviewAnalysisFailedException.finalFailure("Jev가 요청을 거절했습니다. status=400", null);
		given(reviewKeywordService.findPendingTargets(anyInt())).willReturn(List.of(FIRST));
		given(reviewKeywordAnalyzer.analyze(FIRST.input())).willThrow(failure);
		sut.analyzePending();

		// when
		sut.analyzePending();

		// then
		then(reviewKeywordService).should(times(2)).fail(FIRST, failure, false);
	}

	private static ReviewKeywordTarget target(Long requestId) {
		return new ReviewKeywordTarget(requestId, requestId * 10, LocalDateTime.of(2026, 11, 1, 0, 0),
			new ReviewKeywordInput(requestId * 10, "청송 꿀사과", "아삭하고 달아요", ProductCategory.FRUIT));
	}

	private static ReviewAnalyzerUnavailableException unavailable() {
		return new ReviewAnalyzerUnavailableException("Jev가 인증을 거절했습니다. status=401", null);
	}

	private static ReviewKeywordAnalysis analysis() {
		return new ReviewKeywordAnalysis(Map.of("C02", 0.95), "v0.3", "jev-1.14.0");
	}

	// 쉬는 시간 전후를 확인하려면 테스트 안에서 시각을 넘겨야 해 고정 Clock 대신 쓴다
	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}
	}
}
