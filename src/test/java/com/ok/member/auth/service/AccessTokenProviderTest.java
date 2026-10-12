package com.ok.member.auth.service;

import com.ok.member.MemberRole;
import com.ok.member.auth.config.JwtConfig;
import com.ok.member.auth.config.JwtProperties;
import com.ok.member.auth.domain.AccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AccessTokenProviderTest {

    private static final String ISSUER = "paldo-gotgan";
    private static final String SECRET = "test-secret-key-for-jwt-must-be-32-bytes-or-more";
    private static final Duration EXPIRATION = Duration.ofMinutes(30);

    private final JwtConfig jwtConfig = new JwtConfig();
    private final JwtProperties properties = new JwtProperties(ISSUER, SECRET, EXPIRATION);

    private AccessTokenProvider accessTokenProvider;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        accessTokenProvider = new AccessTokenProvider(jwtConfig.jwtEncoder(properties), properties);
        jwtDecoder = jwtConfig.jwtDecoder(properties);
    }

    @Test
    @DisplayName("발급한 토큰은 디코더로 검증되고, subject와 role claim이 회원 정보와 일치한다")
    void issue_containsMemberInfo() {
        // given
        Long memberId = 1L;

        // when
        AccessToken accessToken = accessTokenProvider.issue(memberId, MemberRole.BUYER);

        // then
        Jwt jwt = jwtDecoder.decode(accessToken.value());
        assertThat(jwt.getSubject()).isEqualTo("1");
        assertThat(jwt.getClaimAsString(AccessTokenProvider.ROLE_CLAIM)).isEqualTo("BUYER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("만료 시각은 발급 시각으로부터 설정한 유효 기간 뒤이고, 반환값과 토큰의 exp가 일치한다")
    void issue_expiration() {
        // given
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        // when
        AccessToken accessToken = accessTokenProvider.issue(1L, MemberRole.BUYER);

        // then
        Jwt jwt = jwtDecoder.decode(accessToken.value());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(EXPIRATION);
        assertThat(jwt.getExpiresAt()).isEqualTo(accessToken.expiresAt());
        assertThat(jwt.getIssuedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("다른 비밀키로 서명된 토큰은 검증에 실패한다")
    void decode_differentSecret() {
        // given
        JwtProperties otherProperties = new JwtProperties(
                ISSUER, "another-secret-key-for-jwt-must-be-32-bytes-long", EXPIRATION);
        AccessTokenProvider otherProvider =
                new AccessTokenProvider(jwtConfig.jwtEncoder(otherProperties), otherProperties);
        AccessToken forged = otherProvider.issue(1L, MemberRole.ADMIN);

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(forged.value()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("권한을 ADMIN으로 변조한 토큰은 서명 검증에 실패한다")
    void decode_tampered() {
        // given
        String token = accessTokenProvider.issue(1L, MemberRole.BUYER).value();

        // JWT는 header.payload.signature 구조이고, payload는 Base64URL로 인코딩된 JSON이다.
        // 공격자가 payload의 role을 BUYER → ADMIN으로 바꾸고 서명은 그대로 둔 상황을 만든다.
        String[] parts = token.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tamperedJson = payloadJson.replace("\"BUYER\"", "\"ADMIN\"");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        // when & then
        assertThat(tamperedJson).contains("ADMIN");
        assertThatThrownBy(() -> jwtDecoder.decode(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("발급자가 다른 토큰은 검증에 실패한다")
    void decode_differentIssuer() {
        // given
        JwtProperties otherIssuer = new JwtProperties("other-service", SECRET, EXPIRATION);
        AccessTokenProvider otherProvider =
                new AccessTokenProvider(jwtConfig.jwtEncoder(otherIssuer), otherIssuer);
        AccessToken token = otherProvider.issue(1L, MemberRole.BUYER);

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(token.value()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("AccessToken의 toString에 토큰 문자열이 노출되지 않는다")
    void accessToken_toString() {
        // given
        AccessToken accessToken = accessTokenProvider.issue(1L, MemberRole.BUYER);

        // when
        String result = accessToken.toString();

        // then
        assertThat(result).doesNotContain(accessToken.value());
        assertThat(result).contains("value=****");
    }

}
