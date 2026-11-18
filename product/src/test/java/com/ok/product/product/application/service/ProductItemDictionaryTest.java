package com.ok.product.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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

	@ParameterizedTest
	@MethodSource("상품명과_한_글자_품목")
	void 가장_긴_품목이_한_글자_품목_표에_있으면_그_한_글자_품목을_고른다(String productName, String expected) {
		// given
		ProductItemDictionary dictionary = new ProductItemDictionary();

		// when
		Optional<String> sut = dictionary.findSingleCharItem(productName);

		// then
		assertThat(sut).hasValue(expected);
	}

	static Stream<Arguments> 상품명과_한_글자_품목() {
		return Stream.of(
			Arguments.of("제주감귤", "귤"),
			Arguments.of("서귀포 하우스감귤", "귤"),
			Arguments.of("나주배5kg", "배"),
			Arguments.of("신고배선물세트", "배"),
			Arguments.of("청도 단감", "감"),
			Arguments.of("대파 1단", "파"),
			Arguments.of("찹쌀 4kg", "쌀")
		);
	}

	@ParameterizedTest
	@MethodSource("한_글자_품목_표에_없는_품목의_상품명")
	void 가장_긴_품목이_한_글자_품목_표에_없으면_빈_값이다(String productName) {
		// given
		ProductItemDictionary dictionary = new ProductItemDictionary();

		// when
		Optional<String> sut = dictionary.findSingleCharItem(productName);

		// then
		assertThat(sut).isEmpty();
	}

	static Stream<String> 한_글자_품목_표에_없는_품목의_상품명() {
		return Stream.of("해남배추3kg", "강원 감자", "상주 곶감", "제주 한라봉", "도라지배즙", "한우 등심 1kg");
	}

	@ParameterizedTest
	@MethodSource("오타_검색어와_제안_품목")
	void 품목과_자모_하나가_다른_검색어면_그_품목을_제안한다(String keyword, String expected) {
		// given
		ProductItemDictionary dictionary = new ProductItemDictionary();

		// when
		Optional<String> sut = dictionary.suggest(keyword);

		// then
		assertThat(sut).hasValue(expected);
	}

	static Stream<Arguments> 오타_검색어와_제안_품목() {
		return Stream.of(
			Arguments.of("고추가루", "고춧가루"),
			Arguments.of("고추 가루", "고춧가루"),
			Arguments.of("샤인머스켓", "샤인머스캣"),
			Arguments.of("시레기", "시래기")
		);
	}

	@ParameterizedTest
	@MethodSource("제안하지_않는_검색어")
	void 품목_그대로이거나_한_글자이거나_맞는_품목이_없으면_제안하지_않는다(String keyword) {
		// given
		ProductItemDictionary dictionary = new ProductItemDictionary();

		// when
		Optional<String> sut = dictionary.suggest(keyword);

		// then
		assertThat(sut).isEmpty();
	}

	static Stream<String> 제안하지_않는_검색어() {
		return Stream.of("고추", "사과", "고구마", "귤", "아이 간식", "", " ", null);
	}

	@Test
	void 제안할_품목이_여럿이면_품목_사전에서_먼저_나온_품목이다() {
		// given
		ProductItemDictionary dictionary = new ProductItemDictionary(List.of("감자", "부추", "고추", "배추"), Map.of());

		// when
		Optional<String> sut = dictionary.suggest("보추");

		// then
		assertThat(sut).hasValue("부추");
	}

	@Test
	void 품목이_하나도_없으면_만들지_못한다() {
		// when & then
		assertThatThrownBy(() -> new ProductItemDictionary(List.of(" ", "·"), Map.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("품목 사전에 품목이 없습니다. location=product-search/items.json");
	}

	@Test
	void 한_글자_품목_표의_키가_한_글자가_아니면_만들지_못한다() {
		// when & then
		assertThatThrownBy(() -> new ProductItemDictionary(List.of("감귤"), Map.of("감귤", List.of("감귤"))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("한 글자 품목 표의 키가 한 글자가 아닙니다. key=감귤, location=product-search/single-char-items.json");
	}

	@Test
	void 한_글자_품목_표에_품목_사전에_없는_품목이_있으면_만들지_못한다() {
		// when & then
		assertThatThrownBy(() -> new ProductItemDictionary(List.of("귤"), Map.of("귤", List.of("귤", "감귤"))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("한 글자 품목 표에 품목 사전에 없는 품목이 있습니다. item=감귤, "
				+ "location=product-search/single-char-items.json");
	}

	@Test
	void 한_품목이_두_한_글자_품목에_들어_있으면_만들지_못한다() {
		// given
		List<String> items = List.of("귤", "감", "감귤");
		Map<String, List<String>> singleCharItems = Map.of("귤", List.of("감귤"), "감", List.of("감귤"));

		// when & then
		assertThatThrownBy(() -> new ProductItemDictionary(items, singleCharItems))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("한 글자 품목 표에 같은 품목이 두 번 있습니다. item=감귤, "
				+ "location=product-search/single-char-items.json");
	}

	@Test
	void 품목이_검색_품목_컬럼_길이를_넘으면_사전을_만들_수_없다() {
		// given
		String tooLongItem = "가".repeat(21);

		// when & then
		assertThatThrownBy(() -> new ProductItemDictionary(List.of("귤", tooLongItem), Map.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("search_item");
	}
}
