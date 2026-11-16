package com.ok.product.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SearchTextNormalizerTest {

	@ParameterizedTest
	@MethodSource("정규화할_문자열")
	void 한글_영문_숫자만_남기고_나머지는_공백_하나로_바꾼다(String value, String expected) {
		// when
		String sut = SearchTextNormalizer.text(value);

		// then
		assertThat(sut).isEqualTo(expected);
	}

	static Stream<Arguments> 정규화할_문자열() {
		return Stream.of(
			Arguments.of("김장·김치용", "김장 김치용"),
			Arguments.of("GAP인증  Apple!", "gap인증 apple"),
			Arguments.of("+ - * \" ( ) @ ~ < >", ""),
			Arguments.of(null, ""),
			Arguments.of("   ", "")
		);
	}

	@Test
	void compact는_공백을_모두_뺀다() {
		// when
		String sut = SearchTextNormalizer.compact("샤인 머스켓");

		// then
		assertThat(sut).isEqualTo("샤인머스켓");
	}

	@Test
	void NFD로_들어온_한글도_NFC와_같은_결과를_낸다() {
		// given
		String nfc = "청송 사과";
		String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
		assertThat(nfd).isNotEqualTo(nfc);

		// when
		List<String> sut = List.of(
			SearchTextNormalizer.text(nfd), SearchTextNormalizer.compact(nfd), SearchTextNormalizer.jamo(nfd));

		// then
		assertThat(sut).containsExactly(
			SearchTextNormalizer.text(nfc), SearchTextNormalizer.compact(nfc), SearchTextNormalizer.jamo(nfc));
	}

	@ParameterizedTest
	@MethodSource("자모로_나눌_문자열")
	void 한글_음절을_초성_중성_종성으로_나눈다(String value, String expected) {
		// when
		String sut = SearchTextNormalizer.jamo(value);

		// then
		assertThat(sut).isEqualTo(expected);
	}

	static Stream<Arguments> 자모로_나눌_문자열() {
		return Stream.of(
			Arguments.of("사과", "ㅅㅏㄱㅘ"),
			Arguments.of("값", "ㄱㅏㅄ"),
			Arguments.of("5kg배", "5kgㅂㅐ")
		);
	}
}
