package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerShippingProfileTest {

    @ParameterizedTest
    @MethodSource("배송_설정")
    void 저장한_값으로_배송_설정을_되돌린다(SellerShippingSetting setting) {
        // when
        SellerShippingProfile sut = SellerShippingProfile.create(1L, setting);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getSellerId()).isEqualTo(1L);
            softly.assertThat(sut.getBaseShippingFee()).isEqualTo(setting.baseShippingFee());
            softly.assertThat(sut.getFreeShippingThreshold()).isEqualTo(setting.freeShippingThreshold());
            softly.assertThat(sut.getCutoffTime()).isEqualTo(setting.cutoffTime());
            softly.assertThat(sut.toSetting()).isEqualTo(setting);
        });
    }

    static Stream<SellerShippingSetting> 배송_설정() {
        return Stream.of(
                new SellerShippingSetting(5_000, null, LocalTime.of(15, 0)),
                new SellerShippingSetting(2_500, 0, LocalTime.of(10, 0)),
                new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0))
        );
    }

    @Test
    void 판매자_ID가_없으면_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> SellerShippingProfile.create(null, new SellerShippingSetting(3_000, null, LocalTime.of(13, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매자 ID는 필수입니다.");
    }

    @Test
    void 배송_설정이_없으면_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> SellerShippingProfile.create(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송 설정은 필수입니다.");
    }
}
