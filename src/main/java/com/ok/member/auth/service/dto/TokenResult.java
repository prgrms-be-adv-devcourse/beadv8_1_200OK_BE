package com.ok.member.auth.service.dto;

import java.time.Instant;

/**
 * 로그인으로 발급한 토큰과 만료 시각을 담는다.
 * 두 만료 시각을 모두 {@link Instant}로 맞춰, 응답에서 시간대 해석 없이 같은 형식(UTC)으로 내보내게 한다.
 */
public record TokenResult(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {

    /**
     * 토큰은 그 자체로 인증 수단이라 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "TokenResult[accessToken=****, accessTokenExpiresAt=%s, refreshToken=****, refreshTokenExpiresAt=%s]"
                .formatted(accessTokenExpiresAt, refreshTokenExpiresAt);
    }

}
