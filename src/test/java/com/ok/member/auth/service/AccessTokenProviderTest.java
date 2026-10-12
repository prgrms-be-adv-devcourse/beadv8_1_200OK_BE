package com.ok.member.auth.service;

import com.ok.member.MemberRole;
import com.ok.member.auth.config.JwtConfig;
import com.ok.member.auth.config.JwtProperties;
import com.ok.member.auth.domain.AccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring을 띄우지 않고, JwtConfig가 만드는 실제 인코더·디코더로 발급과 검증을 함께 확인한다.
 * mock 인코더를 쓰면 "실제로 검증 가능한 토큰인지"를 확인할 수 없기 때문이다.
 */
public class AccessTokenProviderTest {

    private static final String ISSUER = "paldo-gotgan";
    /** "test-secret-key-for-jwt-must-be-32-bytes-or-more"를 Base64로 인코딩한 값 (application-test.yml과 같은 형식) */
    private static final String SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtbXVzdC1iZS0zMi1ieXRlcy1vci1tb3Jl";
    /** "another-secret-key-for-jwt-must-be-32-bytes-long"을 Base64로 인코딩한 값 */
    private static final String OTHER_SECRET = "YW5vdGhlci1zZWNyZXQta2V5LWZvci1qd3QtbXVzdC1iZS0zMi1ieXRlcy1sb25n";
    private static final Duration EXPIRATION = Duration.ofMinutes(30);

    private final JwtConfig jwtConfig = new JwtConfig();
    private final JwtProperties properties = new JwtProperties(ISSUER, SECRET, EXPIRATION);

    private JwtEncoder jwtEncoder;
    private JwtDecoder jwtDecoder;
    private AccessTokenProvider accessTokenProvider;

    @BeforeEach
    void setUp() {
        jwtEncoder = jwtConfig.jwtEncoder(properties);
        jwtDecoder = jwtConfig.jwtDecoder(properties);
        accessTokenProvider = new AccessTokenProvider(jwtEncoder, properties);
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
    @DisplayName("토큰마다 고유한 jti가 발급된다")
    void issue_uniqueJti() {
        // when
        Jwt first = jwtDecoder.decode(accessTokenProvider.issue(1L, MemberRole.BUYER).value());
        Jwt second = jwtDecoder.decode(accessTokenProvider.issue(1L, MemberRole.BUYER).value());

        // then
        assertThat(first.getId()).isNotBlank();
        assertThat(second.getId()).isNotBlank();
        assertThat(first.getId()).isNotEqualTo(second.getId());
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
    @DisplayName("memberId가 null이면 토큰을 발급하지 않고 예외가 발생한다")
    void issue_nullMemberId() {
        // when & then
        assertThatThrownBy(() -> accessTokenProvider.issue(null, MemberRole.BUYER))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("memberId");
    }

    @Test
    @DisplayName("role이 null이면 토큰을 발급하지 않고 예외가 발생한다")
    void issue_nullRole() {
        // when & then
        assertThatThrownBy(() -> accessTokenProvider.issue(1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("role");
    }

    @Test
    @DisplayName("만료 시각이 지난 토큰은 허용 오차 없이 검증에 실패한다")
    void decode_expired() {
        // given
        // 1초 전에 만료된 토큰을 같은 키로 직접 만든다. (기본 허용 오차 60초였다면 통과했을 토큰)
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("1")
                .issuedAt(now.minus(EXPIRATION).minusSeconds(1))
                .expiresAt(now.minusSeconds(1))
                .claim(AccessTokenProvider.ROLE_CLAIM, "BUYER")
                .build();
        JwsHeader header = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM).build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 비밀키로 서명된 토큰은 검증에 실패한다")
    void decode_differentSecret() {
        // given
        JwtProperties otherProperties = new JwtProperties(ISSUER, OTHER_SECRET, EXPIRATION);
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
