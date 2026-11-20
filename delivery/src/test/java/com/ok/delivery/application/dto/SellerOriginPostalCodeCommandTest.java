package com.ok.delivery.application.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerOriginPostalCodeCommandTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 13, 10, 0);

    @Test
    void 숫자_5자리_우편번호는_만들_수_있다() {
        // when
        SellerOriginPostalCodeCommand sut = new SellerOriginPostalCodeCommand(10L, "63100", OCCURRED_AT);

        // then
        assertThat(sut.postalCode()).isEqualTo("63100");
    }

    @Test
    void 우편번호가_없어도_만들_수_있다() {
        // when & then: 출고지를 지운 메시지
        assertThatCode(() -> new SellerOriginPostalCodeCommand(10L, null, OCCURRED_AT))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("잘못된_입력")
    void 잘못된_입력은_예외가_난다(Long sellerId, String postalCode, LocalDateTime occurredAt) {
        // when & then
        assertThatThrownBy(() -> new SellerOriginPostalCodeCommand(sellerId, postalCode, occurredAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> 잘못된_입력() {
        return Stream.of(
                Arguments.of(null, "63100", OCCURRED_AT),
                Arguments.of(10L, "63100", null),
                Arguments.of(10L, "6310", OCCURRED_AT),
                Arguments.of(10L, "631000", OCCURRED_AT),
                Arguments.of(10L, "6310a", OCCURRED_AT),
                Arguments.of(10L, "", OCCURRED_AT)
        );
    }
}
