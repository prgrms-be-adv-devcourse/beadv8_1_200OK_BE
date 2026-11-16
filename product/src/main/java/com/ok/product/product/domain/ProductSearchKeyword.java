package com.ok.product.product.domain;

import java.util.Optional;

/**
 * 구매자가 입력한 검색어를 검색 행({@link ProductSearch})과 같은 규칙으로 정규화한 세 가지 형태를 담는다.
 * 검색 행의 컬럼마다 비교하는 형태가 달라 한 번에 만들어 함께 넘긴다.
 *
 * @param text    {@code search_body}, {@code keywords}와 비교한다
 * @param compact 공백을 없앤 상품명({@code name_compact})과 비교한다
 * @param jamo    자모로 나눈 상품명({@code name_jamo})과 비교한다. 오타가 있어도 찾기 위해서다
 */
public record ProductSearchKeyword(String text, String compact, String jamo) {

	/**
	 * 검색어를 정규화한다. null이거나 정규화한 뒤 남는 글자가 없으면(공백, 기호뿐) 빈 값이다.
	 * 남는 글자가 없는 검색어로 찾으면 모든 상품의 점수가 0이 되어 결과가 비므로, 검색어가 없는 것으로 본다.
	 */
	public static Optional<ProductSearchKeyword> from(String keyword) {
		String compact = SearchTextNormalizer.compact(keyword);
		if (compact.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new ProductSearchKeyword(SearchTextNormalizer.text(keyword), compact,
			SearchTextNormalizer.jamo(keyword)));
	}

	/**
	 * 공백·기호를 뺀 검색어가 한 글자인지 알려 준다. 한 글자면 FULLTEXT 대신 한 글자 품목({@code search_item})과 정확히 같은 상품을 찾는다.
	 */
	public boolean isSingleCharacter() {
		return compact.codePointCount(0, compact.length()) == 1;
	}
}
