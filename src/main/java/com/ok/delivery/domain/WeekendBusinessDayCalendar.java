package com.ok.delivery.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 토요일과 일요일만 영업일에서 제외한다.
 * 공휴일은 아직 반영하지 않는다.
 */
public class WeekendBusinessDayCalendar implements BusinessDayCalendar {

    @Override
    public boolean isBusinessDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }
}
