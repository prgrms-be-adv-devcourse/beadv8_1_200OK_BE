package com.ok.product.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProductSortTest {

	@ParameterizedTest
	@MethodSource("검색어_유무와_요청한_정렬")
	void 정렬을_고르지_않으면_검색어가_있을_때_정확도순_없을_때_최신순이고_검색어_없는_정확도순은_최신순이다(
		boolean hasKeyword, ProductSort requested, ProductSort expected) {
		// when
		ProductSort sut = ProductSort.resolve(hasKeyword, requested);

		// then
		assertThat(sut).isEqualTo(expected);
	}

	static Stream<Arguments> 검색어_유무와_요청한_정렬() {
		return Stream.of(
			Arguments.of(true, null, ProductSort.RELEVANCE),
			Arguments.of(false, null, ProductSort.LATEST),
			Arguments.of(true, ProductSort.RELEVANCE, ProductSort.RELEVANCE),
			Arguments.of(false, ProductSort.RELEVANCE, ProductSort.LATEST),
			Arguments.of(true, ProductSort.PRICE_ASC, ProductSort.PRICE_ASC),
			Arguments.of(false, ProductSort.PRICE_ASC, ProductSort.PRICE_ASC),
			Arguments.of(true, ProductSort.PRICE_DESC, ProductSort.PRICE_DESC),
			Arguments.of(false, ProductSort.PRICE_DESC, ProductSort.PRICE_DESC),
			Arguments.of(true, ProductSort.LATEST, ProductSort.LATEST),
			Arguments.of(false, ProductSort.LATEST, ProductSort.LATEST));
	}
}
