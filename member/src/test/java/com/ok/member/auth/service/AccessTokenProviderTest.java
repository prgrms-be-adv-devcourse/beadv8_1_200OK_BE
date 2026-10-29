package com.ok.member.auth.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ok.common.security.TokenClaims;
import com.ok.common.security.TokenRoles;
import com.ok.common.config.JwtConfig;
import com.ok.common.config.JwtProperties;
import com.ok.common.config.RsaPemKeys;
import com.ok.member.auth.config.JwtEncoderConfig;
import com.ok.member.auth.config.JwtSigningProperties;
import com.ok.member.auth.domain.AccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Spring을 띄우지 않고, JwtConfig가 만드는 실제 인코더·디코더로 발급과 검증을 함께 확인한다.
 * mock 인코더를 쓰면 "실제로 검증 가능한 토큰인지"를 확인할 수 없기 때문이다.
 *
 * <p>발급과 검증에 같은 고정 시각(Clock)을 써서, 만료 판단을 실제 시각과 무관하게 정확히 확인한다.
 *
 * <p>디코딩 실패는 메시지 대신 예외 타입으로 검증한다. 메시지는 라이브러리(Nimbus)가 만들어 버전마다 바뀔 수 있고,
 * 타입만으로 실패 원인을 구분할 수 있다.
 * <ul>
 *   <li>{@link BadJwtException}: 서명이나 타입(typ)이 맞지 않는 등 토큰 자체가 잘못됨</li>
 *   <li>{@link JwtValidationException}: 서명은 맞지만 만료, 발급자, 대상 등 검증 규칙 위반</li>
 * </ul>
 */
class AccessTokenProviderTest {

    private static final String ISSUER = "paldo-gotgan";
    private static final String AUDIENCE = "paldo-gotgan-api";
    private static final String KEY_ID = "rs256-v1";
    /** 테스트 전용 키 쌍 (application-test.yml과 같은 파일) */
    private static final Resource PUBLIC_KEY = new ClassPathResource("jwt/test-public.pem");
    private static final Resource PRIVATE_KEY = new ClassPathResource("jwt/test-private.pem");
    private static final Duration ACCESS_EXPIRATION = Duration.ofMinutes(30);
    private static final Duration REFRESH_EXPIRATION = Duration.ofDays(14);
    private static final List<String> BUYER = List.of(TokenRoles.BUYER);

    private final JwtConfig jwtConfig = new JwtConfig();
    private final JwtEncoderConfig jwtEncoderConfig = new JwtEncoderConfig();
    private final JwtProperties properties = properties(ISSUER, AUDIENCE);
    private final JwtSigningProperties signing = new JwtSigningProperties(KEY_ID, PRIVATE_KEY);

    private Instant now;
    private Clock fixedClock;
    private JwtEncoder jwtEncoder;
    private JwtDecoder jwtDecoder;
    private AccessTokenProvider accessTokenProvider;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        fixedClock = Clock.fixed(now, ZoneId.of("Asia/Seoul"));
        jwtEncoder = jwtEncoderConfig.jwtEncoder(properties, signing);
        jwtDecoder = jwtConfig.jwtDecoder(properties, fixedClock);
        accessTokenProvider = new AccessTokenProvider(jwtEncoder, properties, signing, fixedClock);
    }

    @Test
    void 발급한_토큰은_검증되고_회원_id와_권한과_발급자와_대상이_담긴다() {
        // given
        List<String> roles = List.of(TokenRoles.BUYER, TokenRoles.SELLER);

        // when
        AccessToken sut = accessTokenProvider.issue(1L, roles);

        // then
        Jwt jwt = jwtDecoder.decode(sut.value());
        assertThat(jwt).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(jwt.getSubject()).isEqualTo("1");
            softly.assertThat(jwt.getClaimAsStringList(TokenClaims.ROLES))
                    .containsExactly(TokenRoles.BUYER, TokenRoles.SELLER);
            softly.assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
            softly.assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        });
    }

    @Test
    void 발급한_토큰의_헤더에_알고리즘과_타입과_키_식별자가_담긴다() {
        // when
        AccessToken sut = accessTokenProvider.issue(1L, BUYER);

        // then
        Jwt jwt = jwtDecoder.decode(sut.value());
        assertThat(jwt).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(jwt.getHeaders().get("alg").toString()).isEqualTo("RS256");
            softly.assertThat(jwt.getHeaders().get("typ").toString()).isEqualTo(JwtConfig.TOKEN_TYPE);
            softly.assertThat(jwt.getHeaders().get("kid")).isEqualTo(KEY_ID);
        });
    }

    @Test
    void 토큰마다_다른_jti가_발급된다() {
        // when
        AccessToken first = accessTokenProvider.issue(1L, BUYER);
        AccessToken second = accessTokenProvider.issue(1L, BUYER);

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
    void 발급_시각은_현재_시각이고_만료_시각은_유효기간_뒤이며_토큰의_exp와_같다() {
        // when
        AccessToken sut = accessTokenProvider.issue(1L, BUYER);

        // then
        Jwt jwt = jwtDecoder.decode(sut.value());
        assertThat(jwt).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(jwt.getIssuedAt()).isEqualTo(now);
            softly.assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(ACCESS_EXPIRATION));
            softly.assertThat(sut.expiresAt()).isEqualTo(jwt.getExpiresAt());
        });
    }

    @Test
    void 회원_id가_null이면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> accessTokenProvider.issue(null, BUYER))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("memberId는 null일 수 없습니다.");
    }

    @Test
    void 권한_목록이_null이면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> accessTokenProvider.issue(1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("roles는 null일 수 없습니다.");
    }

    @Test
    void 권한_목록이_비어_있으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> accessTokenProvider.issue(1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("roles는 비어 있을 수 없습니다.");
    }

    @Test
    void 만료_시각이_지난_토큰은_허용_오차_없이_거부된다() {
        // given
        // 1초 전에 만료된 토큰 (기본 허용 오차 60초였다면 통과했을 토큰).
        // 발급 시각도 유효기간만큼 앞당겨, 발급 → 만료 순서가 맞는 실제 토큰처럼 만든다.
        String expired = encode(validClaims()
                .issuedAt(now.minus(ACCESS_EXPIRATION).minusSeconds(1))
                .expiresAt(now.minusSeconds(1))
                .build(), validHeader());

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(expired))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 만료_시각이_없는_토큰은_거부된다() {
        // given
        // exp가 없으면 기본 검증기는 만료 검사를 건너뛰어 영원히 유효해진다. 필수 검증이 막는지 확인한다.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("1")
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .claim(TokenClaims.ROLES, BUYER)
                .build();
        String withoutExp = encode(claims, validHeader());

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(withoutExp))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 대상이_다른_토큰은_거부된다() {
        // given
        String otherAudience = encode(validClaims().audience(List.of("other-service")).build(), validHeader());

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(otherAudience))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void 타입이_Access_Token이_아닌_토큰은_거부된다() {
        // given
        // 같은 키로 서명했지만 다른 용도(typ=JWT)로 만든 토큰
        JwsHeader jwtTypeHeader = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM).type("JWT").keyId(KEY_ID).build();
        String otherType = encode(validClaims().build(), jwtTypeHeader);

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(otherType))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void 다른_개인키로_서명된_토큰은_거부된다() throws Exception {
        // given
        // 공격자가 자기 키 쌍을 만들어, 우리 kid를 달고 ADMIN 토큰에 서명한 상황
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair attackerKeys = generator.generateKeyPair();
        JwtEncoder attackerEncoder = rsaEncoder(
                (RSAPublicKey) attackerKeys.getPublic(), (RSAPrivateKey) attackerKeys.getPrivate(), KEY_ID);
        String forged = encode(attackerEncoder, validClaims().claim(TokenClaims.ROLES, List.of(TokenRoles.ADMIN)).build(),
                validHeader());

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(forged))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void 공개키를_HMAC_비밀키로_써서_HS256으로_서명한_토큰은_거부된다() throws Exception {
        // given
        // 알고리즘 혼동 공격: 헤더의 alg를 믿는 검증기는 공개키(누구나 아는 값)를 HMAC 키로 써서 이 서명을 통과시킨다
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(new JOSEObjectType(JwtConfig.TOKEN_TYPE))
                .keyID(KEY_ID)
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("1")
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ACCESS_EXPIRATION)))
                .claim(TokenClaims.ROLES, List.of(TokenRoles.ADMIN))
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new MACSigner(PUBLIC_KEY.getContentAsByteArray()));
        String forged = jwt.serialize();

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(forged))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void 등록되지_않은_kid로_서명된_토큰은_거부된다() {
        // given
        // 서명 자체는 우리 개인키로 했지만, 검증 목록에 없는 kid를 단 토큰
        JwtEncoder unknownKidEncoder = rsaEncoder(
                RsaPemKeys.publicKey(PUBLIC_KEY), RsaPemKeys.privateKey(PRIVATE_KEY), "rs256-unknown");
        JwsHeader header = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM)
                .type(JwtConfig.TOKEN_TYPE)
                .keyId("rs256-unknown")
                .build();
        String token = encode(unknownKidEncoder, validClaims().build(), header);

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void 권한을_ADMIN으로_변조한_토큰은_거부된다() {
        // given
        String token = accessTokenProvider.issue(1L, BUYER).value();

        // JWT는 header.payload.signature 구조이고, payload는 Base64URL로 인코딩된 JSON이다.
        // 공격자가 payload의 권한을 BUYER → ADMIN으로 바꾸고 서명은 그대로 둔 상황을 만든다.
        String[] parts = token.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tamperedJson = payloadJson.replace("\"BUYER\"", "\"ADMIN\"");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        // when & then
        assertThat(tamperedJson).contains("ADMIN");
        assertThatThrownBy(() -> jwtDecoder.decode(tampered))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void 발급자가_다른_토큰은_거부된다() {
        // given
        JwtProperties otherIssuer = properties("other-service", AUDIENCE);
        AccessTokenProvider otherProvider = new AccessTokenProvider(
                jwtEncoderConfig.jwtEncoder(otherIssuer, signing), otherIssuer, signing, fixedClock);
        String token = otherProvider.issue(1L, BUYER).value();

        // when & then
        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void AccessToken의_toString에_토큰_문자열이_노출되지_않는다() {
        // given
        AccessToken accessToken = accessTokenProvider.issue(1L, BUYER);

        // when
        String sut = accessToken.toString();

        // then
        assertThat(sut).doesNotContain(accessToken.value()).contains("value=****");
    }

    private static JwtProperties properties(String issuer, String audience) {
        return new JwtProperties(issuer, audience, List.of(new JwtProperties.VerificationKey(KEY_ID, PUBLIC_KEY)),
                ACCESS_EXPIRATION, REFRESH_EXPIRATION);
    }

    /** 검증 목록과 상관없이 주어진 키 쌍과 kid로 서명하는 인코더. 공격자나 잘못된 설정의 발급자를 흉내 낸다. */
    private static JwtEncoder rsaEncoder(RSAPublicKey publicKey, RSAPrivateKey privateKey, String keyId) {
        RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims, JwsHeader header) {
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** 실제 발급과 같은 클레임. 테스트마다 한 가지만 바꿔 검증 규칙을 확인한다. */
    private JwtClaimsSet.Builder validClaims() {
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("1")
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_EXPIRATION))
                .claim(TokenClaims.ROLES, BUYER);
    }

    private JwsHeader validHeader() {
        return JwsHeader.with(JwtConfig.SIGNING_ALGORITHM).type(JwtConfig.TOKEN_TYPE).keyId(KEY_ID).build();
    }

    private String encode(JwtClaimsSet claims, JwsHeader header) {
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

}
