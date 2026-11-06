package com.ok.product.review.service.dto;

import com.ok.product.product.domain.ProductCategory;

/**
 * 키워드 분석기에 넘기는 리뷰 본문 하나다.
 * 분석기는 상품의 현재 하위 분류에 적용되는 키워드만 묻는다. 옵션명은 정밀도를 잰 실험 조건에 없어 넘기지 않는다.
 *
 * @param choiceSeed 선택지 순서를 섞는 시드. 분석기에 보내지 않는다. 같은 시드는 늘 같은 순서를 받는다.
 *                   작업자는 리뷰 ID를, 관리자 분석 테스트는 본문 해시를 쓴다
 */
public record ReviewKeywordInput(
	long choiceSeed,
	String productName,
	String content,
	ProductCategory category
) {

	/**
	 * 리뷰 ID가 없는 본문(관리자 분석 테스트)의 입력을 만든다. 본문 해시를 시드로 써서 같은 본문은 같은 선택지 순서를 받는다.
	 */
	public static ReviewKeywordInput seededByContent(String productName, String content, ProductCategory category) {
		// String.hashCode는 명세로 정해진 값이라 재시작하거나 서버가 달라도 같은 본문은 같은 시드다
		return new ReviewKeywordInput(content.hashCode(), productName, content, category);
	}
}
