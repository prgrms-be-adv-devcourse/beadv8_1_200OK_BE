package com.ok.product.review.client;

import org.springframework.stereotype.Component;

import com.ok.product.review.service.dto.ReviewKeywordAnalysis;
import com.ok.product.review.service.dto.ReviewKeywordInput;

/**
 * 실제 키워드 분석기가 들어오기 전까지 키워드 작업자가 기동할 수 있게 두는 자리다. 늘 분석기를 쓸 수 없다고 알린다.
 * 작업자는 실패로 기록하지 않고 쉬므로 요청은 대기로 남는다. 운영 설정에서도 키워드 작업자를 켜지 않는다.
 */
// TODO(#460): Jev 키워드 분석기(JevReviewKeywordAnalyzer)로 바꾸고 이 클래스를 지운다
@Component
public class UnavailableReviewKeywordAnalyzer implements ReviewKeywordAnalyzer {

	@Override
	public ReviewKeywordAnalysis analyze(ReviewKeywordInput input) {
		throw new ReviewAnalyzerUnavailableException("키워드 분석기가 아직 없습니다.", null);
	}
}
