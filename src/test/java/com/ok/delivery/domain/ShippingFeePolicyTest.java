package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ShippingFeePolicyTest {

    private static final SellerShippingSetting SETTING = new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0));
    private static final int NO_SURCHARGE = 0;
    private static final int REMOTE_ISLAND_SURCHARGE = 5_000;

    @ParameterizedTest
    @MethodSource("상품_금액별_기본_배송비")
    void 상품_금액이_무료배송_기준_이상이면_기본_배송비는_0원이다(long itemAmount, int expected) {
        // when
        int actual = ShippingFeePolicy.baseShippingFee(itemAmount, SETTING);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> 상품_금액별_기본_배송비() {
        return Stream.of(
                Arguments.of(0L, 3_000),
                Arguments.of(49_999L, 3_000),
                Arguments.of(50_000L, 0),
                Arguments.of(50_001L, 0)
        );
    }

    @Test
    void 무료배송_기준이_0원이면_항상_무료배송이다() {
        // given
        SellerShippingSetting zeroThreshold = new SellerShippingSetting(3_000, 0, LocalTime.of(13, 0));

        // when
        int actual = ShippingFeePolicy.baseShippingFee(0L, zeroThreshold);

        // then
        assertThat(actual).isZero();
    }

    @Test
    void 일반_지역은_추가_운임이_없다() {
        // when
        SellerShippingFee sut = ShippingFeePolicy.calculate(1L, 10_000L, SETTING, NO_SURCHARGE);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.sellerId()).isEqualTo(1L);
            softly.assertThat(sut.baseShippingFee()).isEqualTo(3_000);
            softly.assertThat(sut.regionSurcharge()).isZero();
            softly.assertThat(sut.totalFee()).isEqualTo(3_000);
        });
    }

    @Test
    void 무료배송이어도_도서산간_추가_운임은_붙는다() {
        // when
        SellerShippingFee sut = ShippingFeePolicy.calculate(1L, 50_000L, SETTING, REMOTE_ISLAND_SURCHARGE);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.baseShippingFee()).isZero();
            softly.assertThat(sut.regionSurcharge()).isEqualTo(5_000);
            softly.assertThat(sut.totalFee()).isEqualTo(5_000);
        });
    }

    @Test
    void 판매자가_2명이면_추가_운임이_판매자마다_붙어_합산된다() {
        // given
        SellerShippingFee sellerA = ShippingFeePolicy.calculate(1L, 49_999L, SETTING, REMOTE_ISLAND_SURCHARGE);
        SellerShippingFee sellerB = ShippingFeePolicy.calculate(2L, 50_000L, SETTING, REMOTE_ISLAND_SURCHARGE);

        // when
        int actual = ShippingFeePolicy.totalFee(List.of(sellerA, sellerB));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sellerA.totalFee()).isEqualTo(8_000);
            softly.assertThat(sellerB.totalFee()).isEqualTo(5_000);
            softly.assertThat(actual).isEqualTo(13_000);
        });
    }

    @Test
    void 판매자마다_자기_설정으로_기본_배송비를_계산한다() {
        // given
        SellerShippingSetting otherSetting = new SellerShippingSetting(2_500, 30_000, LocalTime.of(15, 0));

        // when
        SellerShippingFee sellerA = ShippingFeePolicy.calculate(1L, 40_000L, SETTING, NO_SURCHARGE);
        SellerShippingFee sellerB = ShippingFeePolicy.calculate(2L, 40_000L, otherSetting, NO_SURCHARGE);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sellerA.baseShippingFee()).isEqualTo(3_000);
            softly.assertThat(sellerB.baseShippingFee()).isZero();
        });
    }

    @Test
    void 상품_금액이_음수면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ShippingFeePolicy.baseShippingFee(-1L, SETTING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상품 금액은 0 이상이어야 합니다.");
    }

    @Test
    void 배송_설정이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ShippingFeePolicy.calculate(1L, 10_000L, null, NO_SURCHARGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매자 배송 설정은 필수입니다.");
    }

    @Test
    void 판매자_ID가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ShippingFeePolicy.calculate(null, 10_000L, SETTING, NO_SURCHARGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sellerId는 필수입니다.");
    }

    @Test
    void 배송비_목록이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ShippingFeePolicy.totalFee(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송비 목록은 필수입니다.");
    }

    @Test
    void 배송비_합계가_int_범위를_넘으면_예외가_발생한다() {
        // given
        List<SellerShippingFee> fees = List.of(
                new SellerShippingFee(1L, Integer.MAX_VALUE, 0),
                new SellerShippingFee(2L, 1, 0)
        );

        // when & then
        assertThatThrownBy(() -> ShippingFeePolicy.totalFee(fees))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void 판매자_배송비가_int_범위를_넘으면_예외가_발생한다() {
        // given
        SellerShippingFee fee = new SellerShippingFee(1L, Integer.MAX_VALUE, 1);

        // when & then
        assertThatThrownBy(fee::totalFee)
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void 추가_운임이_음수면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> ShippingFeePolicy.calculate(1L, 10_000L, SETTING, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("추가 운임은 0 이상이어야 합니다.");
    }
}
