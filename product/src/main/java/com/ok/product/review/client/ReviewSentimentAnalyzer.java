package com.ok.product.review.client;

import com.ok.product.review.service.dto.ReviewSentimentAnalysis;
import com.ok.product.review.service.dto.ReviewSentimentInput;

/**
 * 리뷰 감성을 분석한다. 외부 분석 서비스(Jev)를 이 인터페이스 뒤에 둔다.
 */
public interface ReviewSentimentAnalyzer {

	/**
	 * 리뷰의 전체 감성과 여섯 분석 항목의 감성을 분석한다.
	 *
	 * @throws ReviewAnalysisFailedException 분석하지 못하면. 다시 시도할 만한 실패인지 함께 알린다
	 */
	ReviewSentimentAnalysis analyze(ReviewSentimentInput input);
}
