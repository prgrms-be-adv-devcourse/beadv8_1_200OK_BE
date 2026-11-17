package com.ok.product.review.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.application.dto.ReviewAnalysisPreviewQuery;
import com.ok.product.review.application.dto.ReviewKeywordPreviewResult;
import com.ok.product.review.application.dto.ReviewSentimentPreviewResult;

/**
 * 관리자가 저장하지 않은 문장으로 리뷰 분석을 시험하는 유스케이스.
 */
public interface ReviewAnalysisPreviewUseCase {

	/**
	 * 상품 분류에 묻는 키워드마다 "말함" 확률과 표현했는지 판정을 돌려준다. 내용 없는 본문은 분석기를 부르지 않는다.
	 *
	 * @throws RestApiException 상품이 없거나 삭제됐으면 {@code PRODUCT_NOT_FOUND}, 옵션이 그 상품의 것이 아니면 {@code INVALID_REVIEW_OPTION},
	 *                          분석기가 인증을 거절하면 {@code REVIEW_ANALYZER_UNAVAILABLE}, 그 밖에 분석하지 못하면 {@code REVIEW_ANALYSIS_FAILED}
	 */
	ReviewKeywordPreviewResult analyzeKeywords(ReviewAnalysisPreviewQuery query);

	/**
	 * 전체 감성과 여섯 분석 항목의 감성을 돌려준다. 내용 없는 본문은 분석기를 부르지 않는다.
	 *
	 * @throws RestApiException 상품이 없거나 삭제됐으면 {@code PRODUCT_NOT_FOUND}, 옵션이 그 상품의 것이 아니면 {@code INVALID_REVIEW_OPTION},
	 *                          분석기가 인증을 거절하면 {@code REVIEW_ANALYZER_UNAVAILABLE}, 그 밖에 분석하지 못하면 {@code REVIEW_ANALYSIS_FAILED}
	 */
	ReviewSentimentPreviewResult analyzeSentiment(ReviewAnalysisPreviewQuery query);

}
