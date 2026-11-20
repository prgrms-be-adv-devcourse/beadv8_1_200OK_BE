package com.ok.delivery.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 출고 예정일과 도착 예정일 범위를 계산한다.
 * 마감 시간은 판매자마다 다르므로 판매자(송장)별로 따로 계산한다.
 */
public class DeliveryDatePolicy {

    // 일반 지역 기준 출고 후 도착까지 최소 2영업일. 도서산간은 지역의 추가 소요일을 더한다.
    private static final int MIN_DELIVERY_DAYS = 2;
    // 도착 예정일 범위의 폭. 최대 소요일은 최소보다 1영업일 길다(일반 지역 2~3영업일).
    private static final int ARRIVAL_RANGE_DAYS = 1;

    private final BusinessDayCalendar calendar;

    /**
     * 날짜를 셀 때 쓸 영업일 계산기를 받는다.
     *
     * @throws IllegalArgumentException 영업일 계산기가 없으면
     */
    public DeliveryDatePolicy(BusinessDayCalendar calendar) {
        if (calendar == null) {
            throw new IllegalArgumentException("영업일 계산기는 필수입니다.");
        }
        this.calendar = calendar;
    }

    /**
     * 출고 예정일을 계산한다.
     * 영업일이고 마감 시간 전이면 당일, 아니면 다음 영업일이다. 마감 시각과 같으면 마감이 지난 것으로 본다.
     *
     * @param baseTime 결제 시각. 견적에서는 요청 시각
     * @throws IllegalArgumentException 기준 시각이나 마감 시간이 없으면
     */
    public LocalDate shipDate(LocalDateTime baseTime, LocalTime cutoffTime) {
        if (baseTime == null) {
            throw new IllegalArgumentException("기준 시각은 필수입니다.");
        }
        if (cutoffTime == null) {
            throw new IllegalArgumentException("출고 마감 시간은 필수입니다.");
        }
        LocalDate baseDate = baseTime.toLocalDate();
        if (calendar.isBusinessDay(baseDate) && baseTime.toLocalTime().isBefore(cutoffTime)) {
            return baseDate;
        }
        return calendar.nextBusinessDay(baseDate);
    }

    /**
     * 출고 예정일로부터 도착 예정일 범위를 계산한다.
     *
     * @param extraDeliveryDays 배송지 지역 판정 결과의 추가 소요일(영업일)
     * @throws IllegalArgumentException 출고 예정일이 없거나 추가 소요일이 음수면
     */
    public DeliveryDateRange arrivalRange(LocalDate shipDate, int extraDeliveryDays) {
        if (shipDate == null) {
            throw new IllegalArgumentException("출고 예정일은 필수입니다.");
        }
        if (extraDeliveryDays < 0) {
            throw new IllegalArgumentException("추가 소요일은 0 이상이어야 합니다.");
        }
        LocalDate start = calendar.plusBusinessDays(shipDate, MIN_DELIVERY_DAYS + extraDeliveryDays);
        LocalDate end = calendar.plusBusinessDays(start, ARRIVAL_RANGE_DAYS);
        return new DeliveryDateRange(start, end);
    }
}
