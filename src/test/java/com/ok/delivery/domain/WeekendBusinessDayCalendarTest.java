package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeekendBusinessDayCalendarTest {

    // 2026-10-12(월) ~ 2026-10-18(일)
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 17);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate NEXT_TUESDAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate NEXT_WEDNESDAY = LocalDate.of(2026, 10, 21);

    private final BusinessDayCalendar sut = new WeekendBusinessDayCalendar();

    @ParameterizedTest
    @MethodSource("요일별_영업일_여부")
    void 주말만_영업일이_아니다(LocalDate date, boolean expected) {
        // when
        boolean actual = sut.isBusinessDay(date);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> 요일별_영업일_여부() {
        return Stream.of(
                Arguments.of(MONDAY, true),
                Arguments.of(THURSDAY, true),
                Arguments.of(FRIDAY, true),
                Arguments.of(SATURDAY, false),
                Arguments.of(SUNDAY, false)
        );
    }

    @ParameterizedTest
    @MethodSource("다음_영업일")
    void 다음_영업일은_기준일을_포함하지_않고_주말을_건너뛴다(LocalDate date, LocalDate expected) {
        // when
        LocalDate actual = sut.nextBusinessDay(date);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> 다음_영업일() {
        return Stream.of(
                Arguments.of(THURSDAY, FRIDAY),
                Arguments.of(FRIDAY, NEXT_MONDAY),
                Arguments.of(SATURDAY, NEXT_MONDAY),
                Arguments.of(SUNDAY, NEXT_MONDAY)
        );
    }

    @ParameterizedTest
    @MethodSource("N영업일_후")
    void N영업일_후는_주말을_빼고_센다(LocalDate date, int days, LocalDate expected) {
        // when
        LocalDate actual = sut.plusBusinessDays(date, days);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> N영업일_후() {
        return Stream.of(
                Arguments.of(MONDAY, 0, MONDAY),
                Arguments.of(SATURDAY, 0, SATURDAY),
                Arguments.of(MONDAY, 3, THURSDAY),
                Arguments.of(FRIDAY, 1, NEXT_MONDAY),
                Arguments.of(FRIDAY, 3, NEXT_WEDNESDAY),
                Arguments.of(SATURDAY, 1, NEXT_MONDAY),
                Arguments.of(SATURDAY, 3, NEXT_WEDNESDAY),
                Arguments.of(SUNDAY, 2, NEXT_TUESDAY)
        );
    }

    @Test
    void 영업일_수가_음수면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> sut.plusBusinessDays(MONDAY, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("영업일 수는 0 이상이어야 합니다.");
    }
}
