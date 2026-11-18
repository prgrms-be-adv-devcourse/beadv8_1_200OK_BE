package com.ok.product.product.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 구매자가 입력한 검색어를 검색 행({@link ProductSearch})과 같은 규칙으로 정규화한 세 가지 형태와 가공 표현이 있는지를 담는다.
 * 검색 행의 컬럼마다 비교하는 형태가 달라 한 번에 만들어 함께 넘긴다.
 *
 * @param text            {@code search_body}, {@code keywords}와 비교한다
 * @param compact         공백을 없앤 상품명({@code name_compact})과 비교한다
 * @param jamo            자모로 나눈 상품명({@code name_jamo})과 비교한다. 오타가 있어도 찾기 위해서다
 * @param processedIntent 검색어에 가공 표현(즙, 가루, 청 등)이 있는지. 없으면 농산 가공품의 점수를 낮춘다
 */
public record ProductSearchKeyword(String text, String compact, String jamo, boolean processedIntent) {

	// 품목만 입력한 구매자("사과")는 원물을 찾고, 즙·가루까지 입력한 구매자는 가공품을 찾는다.
	// "청"은 낱말 끝에 올 때만 본다. 앞에 오면 "청송", "청양", "청포도"처럼 산지·품종이다
	private static final Pattern PROCESSED_EXPRESSION = Pattern.compile("즙|가루|기름|말린|말랭이|분말|곶감|누룽지|청(\\s|$)");

	/**
	 * 검색어를 정규화한다. null이거나 정규화한 뒤 남는 글자가 없으면(공백, 기호뿐) 빈 값이다.
	 * 남는 글자가 없는 검색어로 찾으면 모든 상품의 점수가 0이 되어 결과가 비므로, 검색어가 없는 것으로 본다.
	 */
	public static Optional<ProductSearchKeyword> from(String keyword) {
		String compact = SearchTextNormalizer.compact(keyword);
		if (compact.isEmpty()) {
			return Optional.empty();
		}
		String text = SearchTextNormalizer.text(keyword);
		return Optional.of(new ProductSearchKeyword(text, compact, SearchTextNormalizer.jamo(keyword),
			PROCESSED_EXPRESSION.matcher(text).find()));
	}

	/**
	 * 공백·기호를 뺀 검색어가 한 글자인지 알려 준다. 한 글자면 FULLTEXT 대신 한 글자 품목({@code search_item})과 정확히 같은 상품을 찾는다.
	 */
	public boolean isSingleCharacter() {
		return compact.codePointCount(0, compact.length()) == 1;
	}
}
