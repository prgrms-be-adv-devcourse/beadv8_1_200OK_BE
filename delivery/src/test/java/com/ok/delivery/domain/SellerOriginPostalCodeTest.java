package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerOriginPostalCodeTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 13, 10, 0);
    private static final LocalDateTime T2 = T1.plusMinutes(5);

    @Test
    void 생성하면_메시지의_값을_그대로_담는다() {
        // when
        SellerOriginPostalCode sut = SellerOriginPostalCode.create(10L, "63100", T1);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getSellerId()).isEqualTo(10L);
            softly.assertThat(sut.getPostalCode()).isEqualTo("63100");
            softly.assertThat(sut.getSourceOccurredAt()).isEqualTo(T1);
        });
    }

    @Test
    void 우편번호가_없이도_생성할_수_있다() {
        // when
        SellerOriginPostalCode sut = SellerOriginPostalCode.create(10L, null, T1);

        // then
        assertThat(sut.getPostalCode()).isNull();
    }

    @ParameterizedTest
    @MethodSource("잘못된_생성_값")
    void 판매자_ID나_발생_시각이_없으면_생성할_수_없다(Long sellerId, LocalDateTime occurredAt) {
        // when & then
        assertThatThrownBy(() -> SellerOriginPostalCode.create(sellerId, "63100", occurredAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> 잘못된_생성_값() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(null, T1),
                org.junit.jupiter.params.provider.Arguments.of(10L, null)
        );
    }

    @Test
    void 더_새로운_메시지는_값을_덮어쓴다() {
        // given
        SellerOriginPostalCode sut = SellerOriginPostalCode.create(10L, "63100", T1);

        // when
        boolean applied = sut.update("06236", T2);

        // then
        assertThat(applied).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPostalCode()).isEqualTo("06236");
            softly.assertThat(sut.getSourceOccurredAt()).isEqualTo(T2);
        });
    }

    @Test
    void 오래된_메시지는_값을_바꾸지_않는다() {
        // given
        SellerOriginPostalCode sut = SellerOriginPostalCode.create(10L, "06236", T2);

        // when
        boolean applied = sut.update("63100", T1);

        // then
        assertThat(applied).isFalse();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPostalCode()).isEqualTo("06236");
            softly.assertThat(sut.getSourceOccurredAt()).isEqualTo(T2);
        });
    }

    @Test
    void 같은_시각의_메시지는_다시_반영해도_결과가_같다() {
        // given
        SellerOriginPostalCode sut = SellerOriginPostalCode.create(10L, "63100", T1);

        // when
        boolean applied = sut.update("63100", T1);

        // then
        assertThat(applied).isTrue();
        assertThat(sut.getPostalCode()).isEqualTo("63100");
    }

    @Test
    void 더_새로운_메시지의_우편번호가_비어_있으면_출고지를_비운다() {
        // given
        SellerOriginPostalCode sut = SellerOriginPostalCode.create(10L, "63100", T1);

        // when
        sut.update(null, T2);

        // then
        assertThat(sut.getPostalCode()).isNull();
    }

    @Test
    void 수정할_때_발생_시각이_없으면_예외가_난다() {
        // given
        SellerOriginPostalCode sut = SellerOriginPostalCode.create(10L, "63100", T1);

        // when & then
        assertThatThrownBy(() -> sut.update("06236", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
