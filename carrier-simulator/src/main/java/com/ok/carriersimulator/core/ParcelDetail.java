package com.ok.carriersimulator.core;

import java.time.Instant;
import java.util.List;

/**
 * 송장 한 건의 상세.
 *
 * @param registration 알림 등록. 없으면 null
 */
public record ParcelDetail(ParcelView summary, WebhookRegistration registration, List<EventTrace> events) {

    /**
     * @param firstFetchedAt 이 사건이 처음으로 조회 응답에 실려 나간 시각. 아직 아무도 가져가지 않았으면 null
     */
    public record EventTrace(TrackingEvent event, Instant firstFetchedAt) {
    }
}
