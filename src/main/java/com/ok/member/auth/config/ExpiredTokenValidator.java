package com.ok.member.auth.config;

import com.ok.common.security.TokenValidationErrors;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

/**
 * 만료된 토큰에 {@link TokenValidationErrors#EXPIRED} 코드를 남긴다.
 *
 * <p>기본 시각 검증기({@code JwtTimestampValidator})도 만료를 거부하지만, 오류 코드가 위조·형식 오류와 같은
 * {@code invalid_token}이라 원인을 구분할 수 없다. 클라이언트가 "재발급"과 "재로그인" 중 무엇을 할지 정할 수 있도록
 * 만료만 따로 표시한다. 거부 판단 자체는 기존 검증기와 같다.
 */
class ExpiredTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error EXPIRED = new OAuth2Error(
            TokenValidationErrors.EXPIRED, "The access token expired", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt();
        // 허용 오차 없이 만료 시각과 같은 순간부터 만료로 본다 (JwtConfig의 CLOCK_SKEW와 같은 기준)
        if (expiresAt != null && !Instant.now().isBefore(expiresAt)) {
            return OAuth2TokenValidatorResult.failure(EXPIRED);
        }
        return OAuth2TokenValidatorResult.success();
    }

}
