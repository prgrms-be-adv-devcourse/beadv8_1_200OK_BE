package com.ok.product.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProductItemDictionaryTest {

	@ParameterizedTest
	@MethodSource("상품명과_품목")
	void 상품명에_들어_있는_품목_중_가장_긴_것을_고른다(String productName, String expected) {
		// given
		ProductItemDictionary dictionary = new ProductItemDictionary();

		// when
		Optional<String> sut = dictionary.findLongestItem(productName);

		// then
		assertThat(sut).hasValue(expected);
	}

	static Stream<Arguments> 상품명과_품목() {
		return Stream.of(
			Arguments.of("해남배추3kg", "배추"),
			Arguments.of("나주배5kg", "배"),
			Arguments.of("신고배선물세트", "배"),
			Arguments.of("제주감귤", "감귤"),
			Arguments.of("GAP인증 청송홍로사과 프리미엄 선물세트 3kg", "사과"),
			Arguments.of("샤인 머스캣 2kg", "샤인머스캣")
		);
	}

	@Test
	void 사전에_없는_품목이면_빈_값이다() {
		// given
		ProductItemDictionary dictionary = new ProductItemDictionary();

		// when
		Optional<String> sut = dictionary.findLongestItem("한우 등심 1kg");

		// then
		assertThat(sut).isEmpty();
	}

	@Test
	void 품목이_하나도_없으면_만들지_못한다() {
		// when & then
		assertThatThrownBy(() -> new ProductItemDictionary(List.of(" ", "·")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("품목 사전에 품목이 없습니다. location=product-search/items.json");
	}
}
