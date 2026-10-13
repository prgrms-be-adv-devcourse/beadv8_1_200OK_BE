package com.ok.member.auth.service;

import com.ok.member.MemberRole;
import com.ok.member.auth.config.JwtConfig;
import com.ok.member.auth.config.JwtProperties;
import com.ok.member.auth.domain.AccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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
    private static final Duration ACCESS_EXPIRATION = Duration.ofMinutes(30);
    private static final Duration REFRESH_EXPIRATION = Duration.ofDays(14);

    private final JwtConfig jwtConfig = new JwtConfig();
    private final JwtProperties properties = new JwtProperties(ISSUER, SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

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
    void 발급한_토큰은_검증되고_회원_id와_권한과_발급자가_담긴다() {
        // given
        Long memberId = 1L;

        // when
        AccessToken sut = accessTokenProvider.issue(memberId, MemberRole.BUYER);

        // then
        Jwt jwt = jwtDecoder.decode(sut.value());
        assertThat(jwt).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(jwt.getSubject()).isEqualTo("1");
            softly.assertThat(jwt.getClaimAsString(AccessTokenProvider.ROLE_CLAIM)).isEqualTo("BUYER");
            softly.assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        });
    }

    @Test
    void 토큰마다_다른_jti가_발급된다() {
        // when
        AccessToken first = accessTokenProvider.issue(1L, MemberRole.BUYER);
        AccessToken second = accessTokenProvider.issue(1L, MemberRole.BUYER);

        // then
        String firstJti = jwtDecoder.decode(first.value()).getId();
        String secondJti = jwtDecoder.decode(second.value()).getId();
        assertSoftly(softly -> {
            softly.assertThat(firstJti).isNotBlank();
            softly.assertThat(secondJti).isNotBlank();
            softly.assertThat(firstJti).isNotEqualTo(secondJti);
        });
    }

    @Test
    void 만료_시각은_발급_시각으로부터_유효기간_뒤이고_토큰의_exp와_같다() {
        // given
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        // when
        AccessToken sut = accessTokenProvider.issue(1L, MemberRole.BUYER);

        // then
        Jwt jwt = jwtDecoder.decode(sut.value());
        assertThat(jwt).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(ACCESS_EXPIRATION);
            softly.assertThat(jwt.getExpiresAt()).isEqualTo(sut.expiresAt());
            softly.assertThat(jwt.getIssuedAt()).isAfterOrEqualTo(before);
        });
    }

    @Test
    void 회원_id가_null이면_예외가_발생한다() {
        // given
        Long memberId = null;

        // when & then
        assertThatThrownBy(() -> accessTokenProvider.issue(memberId, MemberRole.BUYER))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("memberId는 null일 수 없습니다.");
    }

    @Test
    void 권한이_null이면_예외가_발생한다() {
        // given
        MemberRole role = null;

        // when & then
        assertThatThrownBy(() -> accessTokenProvider.issue(1L, role))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("role은 null일 수 없습니다.");
    }

    @Test
    void 만료_시각이_지난_토큰은_허용_오차_없이_거부된다() {
        // given
        // 1초 전에 만료된 토큰을 같은 키로 직접 만든다. (기본 허용 오차 60초였다면 통과했을 토큰)
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("1")
                .issuedAt(now.minus(ACCESS_EXPIRATION).minusSeconds(1))
                .expiresAt(now.minusSeconds(1))
                .claim(AccessTokenProvider.ROLE_CLAIM, "BUYER")
                .build();
        JwsHeader header = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM).build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(expired))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 다른_비밀키로_서명된_토큰은_거부된다() {
        // given
        JwtProperties otherProperties = new JwtProperties(ISSUER, OTHER_SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);
        AccessTokenProvider otherProvider =
                new AccessTokenProvider(jwtConfig.jwtEncoder(otherProperties), otherProperties);
        String forged = otherProvider.issue(1L, MemberRole.ADMIN).value();

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(forged))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void 권한을_ADMIN으로_변조한_토큰은_거부된다() {
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
        assertThatThrownBy(() -> jwtDecoder.decode(tampered))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void 발급자가_다른_토큰은_거부된다() {
        // given
        JwtProperties otherIssuer = new JwtProperties("other-service", SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);
        AccessTokenProvider otherProvider = new AccessTokenProvider(jwtConfig.jwtEncoder(otherIssuer), otherIssuer);
        String token = otherProvider.issue(1L, MemberRole.BUYER).value();

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void AccessToken의_toString에_토큰_문자열이_노출되지_않는다() {
        // given
        AccessToken accessToken = accessTokenProvider.issue(1L, MemberRole.BUYER);

        // when
        String sut = accessToken.toString();

        // then
        assertThat(sut).doesNotContain(accessToken.value()).contains("value=****");
    }

}
