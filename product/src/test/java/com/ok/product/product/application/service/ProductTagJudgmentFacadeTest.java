package com.ok.product.product.application.service;

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

import com.ok.product.product.application.dto.ProductTagInput;
import com.ok.product.product.application.dto.ProductTagScores;
import com.ok.product.product.application.dto.ProductTagTarget;
import com.ok.product.product.application.port.out.ProductTagJudge;
import com.ok.product.product.exception.ProductTagJudgeFailedException;
import com.ok.product.product.exception.ProductTagJudgeUnavailableException;

@ExtendWith(MockitoExtension.class)
class ProductTagJudgmentFacadeTest {

	private static final ProductTagTarget FIRST = target(1L);
	private static final ProductTagTarget SECOND = target(2L);

	@Mock
	ProductTagService productTagService;

	@Mock
	ProductTagJudge productTagJudge;

	MutableClock clock;
	ProductTagJudgmentFacade sut;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
		sut = new ProductTagJudgmentFacade(productTagService, productTagJudge, clock);
	}

	@Test
	void 재시도할_실패는_재시도로_기록한다() {
		// given
		ProductTagJudgeFailedException failure = ProductTagJudgeFailedException.retryable("Jev 서버 오류입니다. status=503", null);
		given(productTagService.findPendingTargets(anyInt())).willReturn(List.of(FIRST));
		given(productTagJudge.judge(FIRST.input())).willThrow(failure);

		// when
		sut.judgePending();

		// then
		then(productTagService).should().fail(FIRST, failure, true);
		then(productTagService).should(never()).complete(any(), any());
	}

	@Test
	void 최종_실패는_재시도하지_않는_실패로_기록하고_다음_요청은_계속_판정한다() {
		// given
		ProductTagJudgeFailedException failure = ProductTagJudgeFailedException.finalFailure("Jev가 요청을 거절했습니다. status=400", null);
		given(productTagService.findPendingTargets(anyInt())).willReturn(List.of(FIRST, SECOND));
		given(productTagJudge.judge(FIRST.input())).willThrow(failure);
		given(productTagJudge.judge(SECOND.input())).willReturn(scores());

		// when
		sut.judgePending();

		// then
		then(productTagService).should().fail(FIRST, failure, false);
		then(productTagService).should().complete(SECOND, scores());
	}

	@Test
	void 판정기가_분류하지_못한_예외는_재시도하지_않는다() {
		// given
		IllegalStateException bug = new IllegalStateException("판정기 버그");
		given(productTagService.findPendingTargets(anyInt())).willReturn(List.of(FIRST));
		given(productTagJudge.judge(FIRST.input())).willThrow(bug);

		// when
		sut.judgePending();

		// then
		then(productTagService).should().fail(FIRST, bug, false);
	}

	@Test
	void 판정기를_쓸_수_없으면_남은_요청을_판정하지_않고_실패로_기록하지_않는다() {
		// given
		given(productTagService.findPendingTargets(anyInt())).willReturn(List.of(FIRST, SECOND));
		given(productTagJudge.judge(FIRST.input())).willThrow(unavailable());

		// when
		sut.judgePending();

		// then
		then(productTagJudge).should(never()).judge(SECOND.input());
		then(productTagService).should(never()).fail(any(), any(), anyBoolean());
		then(productTagService).should(never()).complete(any(), any());
	}

	@Test
	void 판정기를_쓸_수_없으면_5분_동안_요청을_꺼내지_않는다() {
		// given
		given(productTagService.findPendingTargets(anyInt())).willReturn(List.of(FIRST));
		given(productTagJudge.judge(FIRST.input())).willThrow(unavailable());
		sut.judgePending();
		clock.advance(Duration.ofMinutes(5).minusSeconds(1));

		// when
		sut.judgePending();

		// then
		then(productTagService).should(times(1)).findPendingTargets(anyInt());
	}

	@Test
	void 쉬는_시간_5분이_지나면_다시_판정한다() {
		// given
		given(productTagService.findPendingTargets(anyInt())).willReturn(List.of(FIRST));
		given(productTagJudge.judge(FIRST.input())).willThrow(unavailable()).willReturn(scores());
		sut.judgePending();
		clock.advance(Duration.ofMinutes(5));

		// when
		sut.judgePending();

		// then
		then(productTagService).should().complete(FIRST, scores());
	}

	private static ProductTagTarget target(Long judgmentId) {
		return new ProductTagTarget(judgmentId, judgmentId * 10, LocalDateTime.of(2026, 11, 18, 0, 0),
			new ProductTagInput(judgmentId * 10, "제주 감귤", List.of("2kg 소과")));
	}

	private static ProductTagJudgeUnavailableException unavailable() {
		return new ProductTagJudgeUnavailableException("Jev가 인증을 거절했습니다. status=401", null);
	}

	private static ProductTagScores scores() {
		return new ProductTagScores(Map.of("T03", 0.95), "v3", "jev-1.14.0");
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
			return this;
		}
	}
}
