package com.ok.member.auth.domain;

import java.time.Instant;

/**
 * 발급된 Access Token
 * @param value     JWT 문자열 (Authorization: Bearer {value} 형태로 사용)
 * @param expiresAt 만료 시간
 */
public record AccessToken(String value, Instant expiresAt) {

    /**
     * AccessToken 문자열 마스킹 처리
     */
    @Override
    public String toString() {
        return "AccessToken[value=****, expiresAt=%s]".formatted(expiresAt);
    }

}
