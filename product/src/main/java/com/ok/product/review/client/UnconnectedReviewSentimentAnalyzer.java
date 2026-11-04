package com.ok.product.review.client;

import org.springframework.stereotype.Component;

import com.ok.product.review.service.dto.ReviewSentimentAnalysis;
import com.ok.product.review.service.dto.ReviewSentimentInput;

/**
 * 실제 분석 서비스를 연결하기 전까지 쓰는 분석기다. 부르면 최종 실패한다.
 * 작업자는 기본 꺼짐이라 부를 일이 없다. 작업자가 분석기 빈을 받으므로 실제 분석기가 생기기 전에도 앱이 뜨도록 둔다.
 */
// TODO(#386): Jev 구현으로 바꾸고 이 클래스를 지운다
@Component
public class UnconnectedReviewSentimentAnalyzer implements ReviewSentimentAnalyzer {

	@Override
	public ReviewSentimentAnalysis analyze(ReviewSentimentInput input) {
		throw ReviewAnalysisFailedException.finalFailure("리뷰 감성 분석기가 연결되지 않았습니다.", null);
	}
}
