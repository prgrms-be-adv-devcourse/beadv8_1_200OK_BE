package com.ok.product.product.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 키워드 검색의 점수 하한(product.search.min-score). ngram이라 글자 두 개만 겹친 상관없는 상품도 점수를 받고,
// 오타용 자모 컬럼은 특히 다른 품목과 자주 겹친다. 이런 상품을 하한으로 뺀다
@Component
class ProductSearchPolicy {

	private final double minScore;

	ProductSearchPolicy(@Value("${product.search.min-score}") double minScore) {
		// 검색 SQL은 MATCH로 거르지 않고 하한으로만 거른다. 0 이하이면 검색어와 한 글자도 겹치지 않는 상품까지 모두 나온다
		if (minScore <= 0) {
			throw new IllegalArgumentException("product.search.min-score는 0보다 커야 합니다.");
		}
		this.minScore = minScore;
	}

	public double minScore() {
		return minScore;
	}
}
