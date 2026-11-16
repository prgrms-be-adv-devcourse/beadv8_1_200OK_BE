package com.ok.product.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}
