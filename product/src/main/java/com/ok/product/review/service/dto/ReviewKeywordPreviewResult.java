package com.ok.product.review.service.dto;

import java.util.List;

/**
 * 본문 하나의 키워드 분석 테스트 결과다. 내용 없는 본문이면 분석하지 않아 버전·모델이 없고 키워드가 비어 있다.
 *
 * @param keywords 상품 분류에 묻는 키워드 전부. 확률이 높은 순이다
 */
public record ReviewKeywordPreviewResult(
	boolean analyzed,
	String keywordVersion,
	String model,
	List<ReviewKeywordJudgementResult> keywords
) {

	public static ReviewKeywordPreviewResult notAnalyzed() {
		return new ReviewKeywordPreviewResult(false, null, null, List.of());
	}
}
