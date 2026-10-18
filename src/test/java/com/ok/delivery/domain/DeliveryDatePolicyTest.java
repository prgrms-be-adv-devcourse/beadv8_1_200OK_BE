package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class DeliveryDatePolicyTest {

    // 2026-10-12(월) ~ 2026-10-23(금)
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 13);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 14);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 17);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate NEXT_TUESDAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate NEXT_WEDNESDAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate NEXT_THURSDAY = LocalDate.of(2026, 10, 22);
    private static final LocalDate NEXT_FRIDAY = LocalDate.of(2026, 10, 23);

    private static final LocalTime CUTOFF_13 = LocalTime.of(13, 0);
    private static final LocalTime CUTOFF_15 = LocalTime.of(15, 0);

    private final DeliveryDatePolicy sut = new DeliveryDatePolicy(new WeekendBusinessDayCalendar());

    @ParameterizedTest
    @MethodSource("기준_시각과_마감_시간별_출고_예정일")
    void 영업일_마감_전이면_당일_아니면_다음_영업일에_출고한다(LocalDateTime baseTime, LocalTime cutoffTime, LocalDate expected) {
        // when
        LocalDate actual = sut.shipDate(baseTime, cutoffTime);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> 기준_시각과_마감_시간별_출고_예정일() {
        return Stream.of(
                // 마감 전후
                Arguments.of(MONDAY.atTime(12, 59), CUTOFF_13, MONDAY),
                Arguments.of(MONDAY.atTime(13, 0), CUTOFF_13, TUESDAY),
                Arguments.of(MONDAY.atTime(14, 0), CUTOFF_13, TUESDAY),
                // 같은 시각이어도 판매자 마감 시간에 따라 다르다
                Arguments.of(MONDAY.atTime(14, 0), CUTOFF_15, MONDAY),
                // 금요일 마감 후, 주말
                Arguments.of(FRIDAY.atTime(14, 0), CUTOFF_13, NEXT_MONDAY),
                Arguments.of(SATURDAY.atTime(10, 0), CUTOFF_13, NEXT_MONDAY),
                Arguments.of(SUNDAY.atTime(23, 59), CUTOFF_13, NEXT_MONDAY)
        );
    }

    @ParameterizedTest
    @MethodSource("출고_예정일과_추가_소요일별_도착_예정일")
    void 도착_예정일은_출고_예정일로부터_2_3영업일에_추가_소요일을_더한다(
            LocalDate shipDate, int extraDeliveryDays, LocalDate expectedStart, LocalDate expectedEnd) {
        // when
        DeliveryDateRange actual = sut.arrivalRange(shipDate, extraDeliveryDays);

        // then
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(actual.start()).isEqualTo(expectedStart);
            softly.assertThat(actual.end()).isEqualTo(expectedEnd);
        });
    }

    static Stream<Arguments> 출고_예정일과_추가_소요일별_도착_예정일() {
        return Stream.of(
                Arguments.of(MONDAY, 0, WEDNESDAY, THURSDAY),
                Arguments.of(THURSDAY, 0, NEXT_MONDAY, NEXT_TUESDAY),
                // 도서산간 추가 소요일
                Arguments.of(MONDAY, 1, THURSDAY, FRIDAY),
                Arguments.of(FRIDAY, 2, NEXT_THURSDAY, NEXT_FRIDAY)
        );
    }

    @Test
    void 금요일_마감_후_도서산간_주문은_다음주_월요일_출고_기준으로_도착_예정일을_계산한다() {
        // given
        LocalDate shipDate = sut.shipDate(FRIDAY.atTime(14, 0), CUTOFF_13);

        // when
        DeliveryDateRange actual = sut.arrivalRange(shipDate, 1);

        // then
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(shipDate).isEqualTo(NEXT_MONDAY);
            softly.assertThat(actual.start()).isEqualTo(NEXT_THURSDAY);
            softly.assertThat(actual.end()).isEqualTo(NEXT_FRIDAY);
        });
    }

    @Test
    void 일반_지역의_주말_주문은_월요일_출고_기준으로_계산한다() {
        // given
        LocalDate shipDate = sut.shipDate(SATURDAY.atTime(10, 0), CUTOFF_13);

        // when
        DeliveryDateRange actual = sut.arrivalRange(shipDate, 0);

        // then
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(actual.start()).isEqualTo(NEXT_WEDNESDAY);
            softly.assertThat(actual.end()).isEqualTo(NEXT_THURSDAY);
        });
    }

    @Test
    void 기준_시각이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> sut.shipDate(null, CUTOFF_13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("기준 시각은 필수입니다.");
    }

    @Test
    void 마감_시간이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> sut.shipDate(MONDAY.atTime(10, 0), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("출고 마감 시간은 필수입니다.");
    }

    @Test
    void 출고_예정일이_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> sut.arrivalRange(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("출고 예정일은 필수입니다.");
    }

    @Test
    void 추가_소요일이_음수면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> sut.arrivalRange(MONDAY, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("추가 소요일은 0 이상이어야 합니다.");
    }
}
