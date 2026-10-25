package com.ok.common.config;

import com.ok.common.security.TokenValidationErrors;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료를 판단하는 유일한 검증기라, 허용 오차와 경계 시점을 정확히 지키는지 확인한다.
 */
class ExpiredTokenValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void 허용_오차_안에서_만료된_토큰은_통과시킨다() {
        // given
        // 허용 오차 30초, 10초 전에 만료된 토큰
        Jwt jwt = jwtExpiringAt(NOW.minusSeconds(10));

        // when
        OAuth2TokenValidatorResult sut = new ExpiredTokenValidator(Duration.ofSeconds(30), CLOCK).validate(jwt);

        // then
        assertThat(sut.hasErrors()).isFalse();
    }

    @Test
    void 허용_오차를_넘겨_만료된_토큰은_만료_코드로_거부한다() {
        // given
        Jwt jwt = jwtExpiringAt(NOW.minusSeconds(31));

        // when
        OAuth2TokenValidatorResult sut = new ExpiredTokenValidator(Duration.ofSeconds(30), CLOCK).validate(jwt);

        // then
        assertThat(sut.getErrors())
                .extracting(error -> error.getErrorCode())
                .containsExactly(TokenValidationErrors.EXPIRED);
    }

    @Test
    void 만료_시각과_같은_순간은_유효로_본다() {
        // given
        Jwt jwt = jwtExpiringAt(NOW);

        // when
        OAuth2TokenValidatorResult sut = new ExpiredTokenValidator(Duration.ZERO, CLOCK).validate(jwt);

        // then
        assertThat(sut.hasErrors()).isFalse();
    }

    @Test
    void 만료_시각이_없으면_판단하지_않는다() {
        // given
        // exp가 없는 토큰은 JwtConfig의 exp 필수 검증이 거부한다. 이 검증기는 만료 여부만 본다.
        Jwt jwt = jwtBuilder().issuedAt(NOW.minusSeconds(60)).build();

        // when
        OAuth2TokenValidatorResult sut = new ExpiredTokenValidator(Duration.ZERO, CLOCK).validate(jwt);

        // then
        assertThat(sut.hasErrors()).isFalse();
    }

    private static Jwt jwtExpiringAt(Instant expiresAt) {
        return jwtBuilder()
                .issuedAt(expiresAt.minus(Duration.ofMinutes(30)))
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * 검증기에 넘길 Jwt 객체를 만든다. 서명하거나 디코딩하지 않으므로 헤더 값은 검증과 무관하다.
     * Jwt는 헤더가 하나 이상 있어야 만들어져서 alg만 채워 둔다.
     */
    private static Jwt.Builder jwtBuilder() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("1");
    }

}
