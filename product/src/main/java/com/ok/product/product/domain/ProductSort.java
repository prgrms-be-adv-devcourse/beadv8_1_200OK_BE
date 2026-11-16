package com.ok.product.product.domain;

// 구매자 상품 목록 정렬. 어떤 정렬이든 품절 상품은 판매 가능 상품 뒤에 온다
public enum ProductSort {
	RELEVANCE,
	PRICE_ASC,
	PRICE_DESC,
	LATEST;

	/**
	 * 요청한 정렬과 검색어 유무로 실제 정렬을 정한다.
	 * 정렬을 고르지 않으면 검색어가 있을 때 정확도순, 없을 때 최신순이다.
	 * 검색어 없이 정확도순을 요청하면 비교할 점수가 없으므로 오류 대신 최신순으로 바꾼다.
	 *
	 * @param requested 요청한 정렬. 고르지 않았으면 null
	 */
	public static ProductSort resolve(boolean hasKeyword, ProductSort requested) {
		if (requested == null || (requested == RELEVANCE && !hasKeyword)) {
			return hasKeyword ? RELEVANCE : LATEST;
		}
		return requested;
	}
}
