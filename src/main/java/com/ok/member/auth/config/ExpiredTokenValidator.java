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
 * 만료된 토큰에 {@link TokenValidationErrors#EXPIRED} 코드를 남긴다.
 *
 * <p>기본 시각 검증기({@code JwtTimestampValidator})도 만료를 거부하지만, 오류 코드가 위조·형식 오류와 같은
 * {@code invalid_token}이라 원인을 구분할 수 없다. 클라이언트가 "재발급"과 "재로그인" 중 무엇을 할지 정할 수 있도록
 * 만료만 따로 표시한다.
 *
 * <p>거부 판단은 기본 검증기와 같아야 한다. 그래서 허용 오차와 현재 시각(Clock)을 같은 값으로 주입받고, 판단식도 똑같이 쓴다.
 * 둘이 어긋나면 이 검증기가 먼저 거부해 기본 검증기의 허용 오차가 무시된다.
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
