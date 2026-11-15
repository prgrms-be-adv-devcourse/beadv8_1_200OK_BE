package com.ok.carriersimulator.core;

import java.time.Instant;

/**
 * 송장에 걸린 알림 등록. 만료 시각이 지나면 알림을 보내지 않는다.
 */
public record WebhookRegistration(String callbackUrl, Instant expirationTime) {

    public boolean activeAt(Instant now) {
        return expirationTime.isAfter(now);
    }
}
