package com.ok.delivery.domain;

import java.time.LocalDate;

/**
 * 날짜가 출고·배송이 이뤄지는 영업일인지 판정한다.
 * 공휴일을 추가할 때 {@link #isBusinessDay}만 다르게 구현하면 되도록, 날짜를 세는 계산은 default 메서드로 둔다.
 */
public interface BusinessDayCalendar {

    boolean isBusinessDay(LocalDate date);

    /** {@code date} 다음 날부터 찾은 첫 영업일을 반환한다. {@code date}가 영업일이어도 포함하지 않는다. */
    default LocalDate nextBusinessDay(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (!isBusinessDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    /**
     * {@code date} 다음 날부터 영업일만 {@code days}번 센 날짜를 반환한다.
     * {@code days}가 0이면 {@code date}를 그대로 반환한다.
     *
     * @throws IllegalArgumentException {@code days}가 음수면
     */
    default LocalDate plusBusinessDays(LocalDate date, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("영업일 수는 0 이상이어야 합니다.");
        }
        LocalDate result = date;
        for (int i = 0; i < days; i++) {
            result = nextBusinessDay(result);
        }
        return result;
    }
}
