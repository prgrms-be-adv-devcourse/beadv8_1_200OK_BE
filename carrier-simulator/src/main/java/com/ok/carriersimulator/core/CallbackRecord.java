package com.ok.carriersimulator.core;

import java.time.Instant;

/**
 * 보낸 알림의 기록. 내가 보낸 알림이 받는 쪽에 도착했는지 확인하는 데 쓴다.
 *
 * @param lastStatus 보낼 때 그 송장의 마지막 상태. 아직 조회되지 않는 송장이면 null
 */
public record CallbackRecord(Instant sentAt, ParcelKey key, StatusCode lastStatus, Integer responseCode, String failure,
                             long elapsedMillis, int attemptNo) {

    public boolean success() {
        return responseCode != null && responseCode >= 200 && responseCode < 300;
    }
}
