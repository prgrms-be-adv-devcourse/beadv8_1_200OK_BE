package com.ok.carriersimulator.core;

import java.util.List;

/**
 * 송장 조회 결과.
 */
public sealed interface TrackResult {

    /**
     * @param events 시각 순으로 정렬한 사건. 마지막 것이 현재 상태다
     */
    record Found(List<TrackingEvent> events) implements TrackResult {

        public TrackingEvent lastEvent() {
            return events.getLast();
        }
    }

    record NotFound() implements TrackResult {
    }
}
