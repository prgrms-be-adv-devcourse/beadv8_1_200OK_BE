package com.ok.delivery.domain;

import java.time.LocalDate;

/**
 * 도착 예정일 범위다. 구매자에게 보여 주고, 배송 지연 감지의 기준으로도 쓴다.
 *
 * @throws IllegalArgumentException 날짜가 없거나 시작이 끝보다 늦으면
 */
public record DeliveryDateRange(LocalDate start, LocalDate end) {

    public DeliveryDateRange {
        if (start == null || end == null) {
            throw new IllegalArgumentException("도착 예정일은 필수입니다.");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("도착 예정일 시작은 끝보다 늦을 수 없습니다.");
        }
    }
}
