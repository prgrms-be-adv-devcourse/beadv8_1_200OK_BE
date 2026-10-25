package com.ok.member.auth.controller.dto;

import com.ok.member.auth.service.dto.TokenResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 로그인·토큰 재발급 응답. 두 API가 같은 형식의 토큰 묶음을 돌려준다.
 */
@Schema(description = "로그인·토큰 재발급 응답")
public record TokenResponse(

        @Schema(description = "토큰 유형. Authorization 헤더에 {tokenType} {accessToken} 형태로 보낸다.",
                example = "Bearer")
        String tokenType,

        @Schema(description = "Access Token (JWT)")
        String accessToken,

        @Schema(description = "Access Token 만료 시각 (UTC, ISO-8601)", example = "2026-10-12T05:30:00Z")
        Instant accessTokenExpiresAt,

        @Schema(description = "Refresh Token. Access Token 재발급에 사용한다.")
        String refreshToken,

        @Schema(description = "Refresh Token 만료 시각 (UTC, ISO-8601)", example = "2026-10-26T05:00:00.123456Z")
        Instant refreshTokenExpiresAt
) {

    private static final String BEARER = "Bearer";

    public static TokenResponse from(TokenResult result) {
        return new TokenResponse(BEARER, result.accessToken(), result.accessTokenExpiresAt(),
                result.refreshToken(), result.refreshTokenExpiresAt());
    }

    /**
     * 토큰은 그 자체로 인증 수단이라 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "TokenResponse[tokenType=%s, accessToken=****, accessTokenExpiresAt=%s, refreshToken=****, refreshTokenExpiresAt=%s]"
                .formatted(tokenType, accessTokenExpiresAt, refreshTokenExpiresAt);
    }

}
