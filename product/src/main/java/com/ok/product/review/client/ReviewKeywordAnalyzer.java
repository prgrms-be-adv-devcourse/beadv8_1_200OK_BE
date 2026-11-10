package com.ok.product.review.client;

import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordInput;

/**
 * 리뷰가 키워드 목록의 키워드를 말했는지 분석한다. 외부 분석 서비스(Jev)를 이 인터페이스 뒤에 둔다.
 * 확률만 돌려주고, 표현했는지 판정은 {@link ReviewKeywordAnalysis}가 한다.
 */
public interface ReviewKeywordAnalyzer {

	/**
	 * 리뷰 상품의 하위 분류에 묻는 키워드마다 "말함" 확률을 분석한다.
	 *
	 * @throws ReviewAnalysisFailedException 분석하지 못하면. 다시 시도할 만한 실패인지 함께 알린다
	 * @throws ReviewAnalyzerUnavailableException 분석기가 어떤 요청도 처리할 수 없으면(인증 거절 등)
	 */
	ReviewKeywordAnalysis analyze(ReviewKeywordInput input);
}
