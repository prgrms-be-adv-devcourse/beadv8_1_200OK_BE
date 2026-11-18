package com.ok.product.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ProductSearchKeywordTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		"배|true",
		"' 배 '|true",
		"배!|true",
		"(귤)|true",
		"A|true",
		"감귤|false",
		"배 추|false",
		"배추!|false"
	})
	void 공백과_기호를_뺀_글자_수가_하나일_때만_한_글자_검색어다(String keyword, boolean expected) {
		// given
		ProductSearchKeyword sut = ProductSearchKeyword.from(keyword).orElseThrow();

		// when & then
		assertThat(sut.isSingleCharacter()).isEqualTo(expected);
	}

	@ParameterizedTest
	@MethodSource("가공_표현_여부별_검색어")
	void 즙_가루_청_같은_가공_표현이_있을_때만_가공품을_찾는_검색어다(String keyword, boolean expected) {
		// given
		ProductSearchKeyword sut = ProductSearchKeyword.from(keyword).orElseThrow();

		// when & then
		assertThat(sut.processedIntent()).isEqualTo(expected);
	}

	static Stream<Arguments> 가공_표현_여부별_검색어() {
		return Stream.of(
			Arguments.of("사과즙", true),
			Arguments.of("고춧가루", true),
			Arguments.of("고추 가루", true),
			Arguments.of("말린 표고버섯", true),
			Arguments.of("유자청", true),
			Arguments.of("과일 청", true),
			Arguments.of("사과", false),
			Arguments.of("청송 사과", false),
			Arguments.of("청양고추", false),
			Arguments.of("청포도", false));
	}
}
