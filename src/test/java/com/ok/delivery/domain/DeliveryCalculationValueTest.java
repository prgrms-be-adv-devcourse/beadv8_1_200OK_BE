package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryCalculationValueTest {

    @ParameterizedTest
    @MethodSource("음수_배송비_금액")
    void 판매자_배송_설정의_금액이_음수면_예외가_발생한다(int baseShippingFee, int freeShippingThreshold) {
        // when & then
        assertThatThrownBy(() -> new SellerShippingSetting(baseShippingFee, freeShippingThreshold, LocalTime.of(13, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송비 금액은 0 이상이어야 합니다.");
    }

    static Stream<Arguments> 음수_배송비_금액() {
        return Stream.of(
                Arguments.of(-1, 50_000),
                Arguments.of(3_000, -1)
        );
    }

    @Test
    void 판매자_배송_설정의_마감_시간이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new SellerShippingSetting(3_000, 50_000, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("출고 마감 시간은 필수입니다.");
    }

    @Test
    void 판매자_배송비의_기본_배송비가_음수면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new SellerShippingFee(1L, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("기본 배송비는 0 이상이어야 합니다.");
    }

    @ParameterizedTest
    @MethodSource("빈_도착_예정일")
    void 도착_예정일이_없으면_예외가_발생한다(LocalDate start, LocalDate end) {
        // when & then
        assertThatThrownBy(() -> new DeliveryDateRange(start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("도착 예정일은 필수입니다.");
    }

    static Stream<Arguments> 빈_도착_예정일() {
        LocalDate date = LocalDate.of(2026, 10, 14);
        return Stream.of(
                Arguments.of(null, date),
                Arguments.of(date, null)
        );
    }

    @Test
    void 도착_예정일_시작이_끝보다_늦으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new DeliveryDateRange(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("도착 예정일 시작은 끝보다 늦을 수 없습니다.");
    }
}
