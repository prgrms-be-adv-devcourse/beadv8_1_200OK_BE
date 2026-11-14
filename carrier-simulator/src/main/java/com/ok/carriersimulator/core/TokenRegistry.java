package com.ok.carriersimulator.core;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 발급한 토큰과 만료 시각을 기억한다.
 */
final class TokenRegistry {

    private final Duration ttl;
    private final Map<String, Instant> expiresAt = new HashMap<>();

    TokenRegistry(Duration ttl) {
        this.ttl = ttl;
    }

    String issue(Instant now) {
        expiresAt.values().removeIf(expiry -> !expiry.isAfter(now));
        // 토큰 값은 흐름 재현과 상관이 없어 시드를 쓰지 않는다
        String token = UUID.randomUUID().toString();
        expiresAt.put(token, now.plus(ttl));
        return token;
    }

    boolean isValid(String token, Instant now) {
        if (token == null) {
            return false;
        }
        Instant expiry = expiresAt.get(token);
        return expiry != null && expiry.isAfter(now);
    }

    void expireAll() {
        expiresAt.clear();
    }

    Duration ttl() {
        return ttl;
    }
}
