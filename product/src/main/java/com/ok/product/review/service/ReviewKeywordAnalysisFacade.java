package com.ok.product.review.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewAnalyzerUnavailableException;
import com.ok.product.review.client.ReviewKeywordAnalyzer;
import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordTarget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 키워드 분석 대기 요청을 꺼내 분석기로 분석하고 결과를 저장한다.
 * 분석기는 외부 서비스라 트랜잭션 밖(여기)에서 부르고, 꺼내기와 결과 저장은 {@link ReviewKeywordService}의 짧은 트랜잭션으로 나눈다.
 * 분석기 실패는 {@link ReviewAnalysisFailedException}이 재시도할 실패라고 밝힌 것만 백오프로 다시 시도하고, 나머지는 바로 최종 실패다.
 * 분석기를 쓸 수 없으면({@link ReviewAnalyzerUnavailableException}) 실패로 기록하지 않고 이번 실행을 멈춘 뒤 5분 동안 쉰다.
 * 감성 작업자({@link ReviewSentimentAnalysisFacade})와 쉬는 상태를 따로 들어, 한쪽이 멈춰도 다른 쪽은 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewKeywordAnalysisFacade {

	private static final int BATCH_SIZE = 20;
	// 임대 시간(10분)보다 짧게 잡아, 키를 고치면 꺼내 둔 요청의 임대가 끝나기 전에 작업자가 다시 돈다
	private static final Duration UNAVAILABLE_PAUSE = Duration.ofMinutes(5);

	private final ReviewKeywordService reviewKeywordService;
	private final ReviewKeywordAnalyzer reviewKeywordAnalyzer;
	private final Clock clock;

	// 앱 인스턴스마다 따로 쉰다. 여러 대면 각 대가 처음 거절을 받을 때 스스로 멈춘다
	private volatile LocalDateTime pausedUntil;

	/**
	 * 다음 시도 시각이 지난 대기 요청을 최대 20건 분석한다. 스케줄러와 통합 테스트가 이 메서드를 부른다.
	 * 분석기를 쓸 수 없어 쉬는 중이면 아무것도 꺼내지 않는다.
	 */
	public void analyzePending() {
		LocalDateTime until = pausedUntil;
		if (until != null && LocalDateTime.now(clock).isBefore(until)) {
			return;
		}
		List<ReviewKeywordTarget> targets = reviewKeywordService.findPendingTargets(BATCH_SIZE);
		for (ReviewKeywordTarget target : targets) {
			try {
				analyze(target);
			} catch (ReviewAnalyzerUnavailableException e) {
				// 남은 요청도 같은 이유로 실패하므로 부르지 않는다. 꺼낸 요청은 대기 상태로 임대(10분)가 끝나면 다시 꺼내지고, 시도 횟수도 그대로다
				pausedUntil = LocalDateTime.now(clock).plus(UNAVAILABLE_PAUSE);
				log.error("리뷰 키워드 분석기를 쓸 수 없어 작업을 멈춤 requestId={}, pausedUntil={}", target.requestId(), pausedUntil, e);
				return;
			} catch (RuntimeException e) {
				// 한 건이 실패해도 나머지는 계속. 저장하지 못한 요청은 대기로 남아 다음 실행에서 다시 꺼낸다
				log.error("리뷰 키워드 분석 결과 저장 실패 requestId={}", target.requestId(), e);
			}
		}
	}

	private void analyze(ReviewKeywordTarget target) {
		ReviewKeywordAnalysis analysis;
		try {
			analysis = reviewKeywordAnalyzer.analyze(target.input());
		} catch (ReviewAnalyzerUnavailableException e) {
			// 아래 RuntimeException 처리에 걸려 최종 실패가 되지 않게 그대로 올린다
			throw e;
		} catch (ReviewAnalysisFailedException e) {
			reviewKeywordService.fail(target, e, e.isRetryable());
			return;
		} catch (RuntimeException e) {
			// 분석기가 분류하지 못한 예외(분석기 버그 등)는 다시 보내도 같은 결과일 가능성이 커 재시도하지 않는다
			reviewKeywordService.fail(target, e, false);
			return;
		}
		reviewKeywordService.complete(target, analysis);
	}
}
