package com.ok.product.review.service;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 5초마다 리뷰 키워드 분석 대기 요청을 처리한다.
 * {@code app.review-analysis.keyword-worker.enabled=true}일 때만 켜진다. 감성 작업자와 속성을 따로 두어 한쪽만 끄고 운영할 수 있다.
 */
@Component
@ConditionalOnProperty(name = "app.review-analysis.keyword-worker.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ReviewKeywordAnalysisScheduler {

	private final ReviewKeywordAnalysisFacade reviewKeywordAnalysisFacade;

	// 분석이 길어져도 밀린 실행을 몰아서 하지 않도록 이전 실행이 끝난 때부터 5초를 센다
	@Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
	public void run() {
		reviewKeywordAnalysisFacade.analyzePending();
	}
}
