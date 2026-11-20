package com.ok.delivery.config;

import com.ok.delivery.domain.SellerShippingSetting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class DefaultShippingPropertiesTest {

    @ParameterizedTest
    @MethodSource("빠진_기본_배송_설정")
    void 기본_배송_설정_값이_빠지면_예외가_발생한다(Integer baseShippingFee, Integer freeShippingThreshold, LocalTime cutoffTime, String message) {
        // when & then
        assertThatThrownBy(() -> new DefaultShippingProperties(baseShippingFee, freeShippingThreshold, cutoffTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> 빠진_기본_배송_설정() {
        LocalTime cutoffTime = LocalTime.of(13, 0);
        return Stream.of(
                Arguments.of(null, 50_000, cutoffTime, "delivery.default-shipping.base-shipping-fee는 필수입니다."),
                Arguments.of(3_000, null, cutoffTime, "delivery.default-shipping.free-shipping-threshold는 필수입니다."),
                Arguments.of(3_000, 50_000, null, "delivery.default-shipping.cutoff-time은 필수입니다.")
        );
    }

    @ParameterizedTest
    @MethodSource("음수_기본_배송비_금액")
    void 기본_배송_설정의_금액이_음수면_예외가_발생한다(int baseShippingFee, int freeShippingThreshold) {
        // when & then
        assertThatThrownBy(() -> new DefaultShippingProperties(baseShippingFee, freeShippingThreshold, LocalTime.of(13, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송비 금액은 0 이상이어야 합니다.");
    }

    static Stream<Arguments> 음수_기본_배송비_금액() {
        return Stream.of(
                Arguments.of(-1, 50_000),
                Arguments.of(3_000, -1)
        );
    }

    @Test
    void 기본_배송_설정을_판매자_배송_설정으로_바꾼다() {
        // given
        DefaultShippingProperties properties = new DefaultShippingProperties(3_000, 50_000, LocalTime.of(13, 0));

        // when
        SellerShippingSetting sut = properties.toSetting();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.baseShippingFee()).isEqualTo(3_000);
            softly.assertThat(sut.freeShippingThreshold()).isEqualTo(50_000);
            softly.assertThat(sut.cutoffTime()).isEqualTo(LocalTime.of(13, 0));
        });
    }
}
