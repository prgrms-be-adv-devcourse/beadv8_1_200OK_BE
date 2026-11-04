package com.ok.product.review.service;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 5초마다 리뷰 감성 분석 대기 요청을 처리한다.
 * {@code app.review-analysis.worker.enabled=true}일 때만 켜진다. 설정이 없으면 꺼져 있어, 로컬·테스트에서 외부 분석 서비스를 부르지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.review-analysis.worker.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ReviewSentimentAnalysisScheduler {

	private final ReviewSentimentAnalysisFacade reviewSentimentAnalysisFacade;

	// 분석이 길어져도 밀린 실행을 몰아서 하지 않도록 이전 실행이 끝난 때부터 5초를 센다
	@Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
	public void run() {
		reviewSentimentAnalysisFacade.analyzePending();
	}
}
