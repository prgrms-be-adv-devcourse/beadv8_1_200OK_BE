package com.ok.order.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerShippingFeeTest {

    @ParameterizedTest
    @MethodSource("음수_금액")
    void 금액이_음수면_만들_수_없다(SellerShippingFeeArgs args) {
        // when & then
        assertThatThrownBy(() -> new SellerShippingFee(
                args.baseShippingFee(), args.freeShippingThreshold(), args.shippingFee(), args.remoteAreaFee()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<SellerShippingFeeArgs> 음수_금액() {
        return Stream.of(
                new SellerShippingFeeArgs(-1L, 50_000L, 3_000L, 0L),
                new SellerShippingFeeArgs(3_000L, -1L, 3_000L, 0L),
                new SellerShippingFeeArgs(3_000L, 50_000L, -1L, 0L),
                new SellerShippingFeeArgs(3_000L, 50_000L, 3_000L, -1L));
    }

    @Test
    void 무료배송_기준이_없으면_null로_만들_수_있다() {
        // when: null은 무료배송 없음 (0이면 항상 무료라 따로 표현한다)
        SellerShippingFee sut = new SellerShippingFee(3_000L, null, 3_000L, 0L);

        // then
        assertThat(sut.freeShippingThreshold()).isNull();
    }

    record SellerShippingFeeArgs(long baseShippingFee, Long freeShippingThreshold, long shippingFee, long remoteAreaFee) {
    }
}
