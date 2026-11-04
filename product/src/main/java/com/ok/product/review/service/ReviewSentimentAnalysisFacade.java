package com.ok.product.review.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewSentimentAnalyzer;
import com.ok.product.review.service.dto.ReviewSentimentAnalysis;
import com.ok.product.review.service.dto.ReviewSentimentTarget;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 감성 분석 대기 요청을 꺼내 분석기로 분석하고 결과를 저장한다.
 * 분석기는 외부 서비스라 트랜잭션 밖(여기)에서 부르고, 꺼내기와 결과 저장은 {@link ReviewSentimentService}의 짧은 트랜잭션으로 나눈다.
 * 분석기 실패는 {@link ReviewAnalysisFailedException}이 재시도할 실패라고 밝힌 것만 백오프로 다시 시도하고, 나머지는 바로 최종 실패다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewSentimentAnalysisFacade {

	private static final int BATCH_SIZE = 20;

	private final ReviewSentimentService reviewSentimentService;
	private final ReviewSentimentAnalyzer reviewSentimentAnalyzer;

	/**
	 * 다음 시도 시각이 지난 대기 요청을 최대 20건 분석한다. 스케줄러와 통합 테스트가 이 메서드를 부른다.
	 */
	public void analyzePending() {
		List<ReviewSentimentTarget> targets = reviewSentimentService.findPendingTargets(BATCH_SIZE);
		for (ReviewSentimentTarget target : targets) {
			try {
				analyze(target);
			} catch (RuntimeException e) {
				// 한 건이 실패해도 나머지는 계속. 저장하지 못한 요청은 대기로 남아 다음 실행에서 다시 꺼낸다
				log.error("리뷰 감성 분석 결과 저장 실패 requestId={}", target.requestId(), e);
			}
		}
	}

	private void analyze(ReviewSentimentTarget target) {
		ReviewSentimentAnalysis analysis;
		try {
			analysis = reviewSentimentAnalyzer.analyze(target.input());
		} catch (ReviewAnalysisFailedException e) {
			reviewSentimentService.fail(target, e, e.isRetryable());
			return;
		} catch (RuntimeException e) {
			// 분석기가 분류하지 못한 예외(분석기 버그 등)는 다시 보내도 같은 결과일 가능성이 커 재시도하지 않는다
			reviewSentimentService.fail(target, e, false);
			return;
		}
		reviewSentimentService.complete(target, analysis);
	}
}
