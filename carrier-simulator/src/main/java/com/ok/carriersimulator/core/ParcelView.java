package com.ok.carriersimulator.core;

import java.time.Instant;

/**
 * 송장 목록의 한 줄.
 *
 * @param manual         상태를 지정해서 보낸 송장인지. 그런 송장은 시나리오가 더 진행하지 않는다
 * @param lastStatus     현재 상태. 아직 조회되지 않는 송장이면 null
 * @param firstQueriedAt 처음 조회된 실제 시각. 아직 한 번도 조회되지 않았으면 null
 */
public record ParcelView(ParcelKey key, Scenario scenario, boolean manual, StatusCode lastStatus,
                         Instant firstQueriedAt, long queryCount, Instant lastQueriedAt) {
}
