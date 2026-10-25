package com.ok.member.auth.config;

import com.ok.common.security.TokenValidationErrors;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 토큰 만료를 판단하고, 만료된 토큰에 {@link TokenValidationErrors#EXPIRED} 코드를 남긴다.
 *
 * <p>Spring Security의 기본 시각 검증기({@code JwtTimestampValidator})는 만료 오류 코드가 위조·형식 오류와 같은
 * {@code invalid_token}이라 원인을 구분할 수 없다. 클라이언트가 "재발급"과 "재로그인" 중 무엇을 할지 정할 수 있도록
 * 만료 판단을 이 검증기가 맡는다. 기본 검증기와 함께 두면 같은 판단을 두 번 해 기준이 어긋날 위험이 생기므로 대신 쓴다.
 *
 * <p>판단식은 기본 검증기와 같다(현재 시각 - 허용 오차 > 만료 시각이면 만료). 기본 검증기가 하던 nbf 검사는 하지 않는다.
 * exp가 없는 토큰은 판단하지 않고, {@code JwtConfig}의 exp 필수 검증이 거부한다.
 */
class ExpiredTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error EXPIRED = new OAuth2Error(
            TokenValidationErrors.EXPIRED, "The access token expired", null);

    private final Duration clockSkew;
    private final Clock clock;

    ExpiredTokenValidator(Duration clockSkew, Clock clock) {
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant expiresAt = jwt.getExpiresAt();
        // JwtTimestampValidator와 같은 판단식: 현재 시각에서 허용 오차를 뺀 시점이 만료 시각을 지나면 만료.
        // exp가 없는 토큰은 여기서 판단하지 않고, JwtConfig의 exp 필수 검증이 거부한다.
        if (expiresAt != null && Instant.now(clock).minus(clockSkew).isAfter(expiresAt)) {
            return OAuth2TokenValidatorResult.failure(EXPIRED);
        }
        return OAuth2TokenValidatorResult.success();
    }

}
