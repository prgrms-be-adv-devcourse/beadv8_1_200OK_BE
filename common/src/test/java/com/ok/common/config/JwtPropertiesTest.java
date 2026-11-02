package com.ok.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class JwtPropertiesTest {

    private static final String ISSUER = "paldo-gotgan";
    private static final String AUDIENCE = "paldo-gotgan-api";
    private static final Resource PUBLIC_KEY = new ClassPathResource("jwt/test-public.pem");
    private static final List<JwtProperties.VerificationKey> KEYS =
            List.of(new JwtProperties.VerificationKey("rs256-v1", PUBLIC_KEY));
    private static final String JWK_SET_URI = "http://member:8080/.well-known/jwks.json";
    private static final Duration ACCESS_EXPIRATION = Duration.ofMinutes(30);
    private static final Duration REFRESH_EXPIRATION = Duration.ofDays(14);

    @Test
    void 올바른_설정값이면_생성된다() {
        // when
        JwtProperties sut = new JwtProperties(ISSUER, AUDIENCE, null, KEYS, null, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.issuer()).isEqualTo(ISSUER);
            softly.assertThat(sut.audience()).isEqualTo(AUDIENCE);
            softly.assertThat(sut.verificationKeys()).isEqualTo(KEYS);
            softly.assertThat(sut.accessTokenExpiration()).isEqualTo(ACCESS_EXPIRATION);
            softly.assertThat(sut.refreshTokenExpiration()).isEqualTo(REFRESH_EXPIRATION);
        });
    }

    @ParameterizedTest
    @MethodSource("비어_있는_문자열")
    void 발급자가_비어_있으면_예외가_발생한다(String issuer) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(issuer, AUDIENCE, null, KEYS, null, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.issuer는 필수입니다.");
    }

    static Stream<String> 비어_있는_문자열() {
        return Stream.of(null, "", " ");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_문자열")
    void 대상이_비어_있으면_예외가_발생한다(String audience) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, audience, null, KEYS, null, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.audience는 필수입니다.");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_키_목록")
    void JWKS_주소가_없는데_검증_키도_없으면_예외가_발생한다(List<JwtProperties.VerificationKey> keys) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, null, keys, null, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.jwk-set-uri가 없으면 jwt.verification-keys가 하나 이상 필요합니다.");
    }

    static Stream<List<JwtProperties.VerificationKey>> 비어_있는_키_목록() {
        return Stream.of(null, List.of());
    }

    @ParameterizedTest
    @MethodSource("비어_있는_키_목록")
    void JWKS_주소가_있으면_검증_키가_없어도_생성된다(List<JwtProperties.VerificationKey> keys) {
        // when
        JwtProperties sut = new JwtProperties(ISSUER, AUDIENCE, JWK_SET_URI, keys, null,
                ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.jwkSetUri()).isEqualTo(JWK_SET_URI);
            softly.assertThat(sut.verificationKeys()).isEmpty();
        });
    }

    // yaml의 ${JWT_JWK_SET_URI:}는 환경변수가 없으면 빈 문자열로 바인딩된다
    @ParameterizedTest
    @MethodSource("비어_있는_문자열")
    void JWKS_주소가_비어_있으면_지정하지_않은_것으로_본다(String jwkSetUri) {
        // when
        JwtProperties sut = new JwtProperties(ISSUER, AUDIENCE, jwkSetUri, KEYS, null,
                ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // then
        assertThat(sut.jwkSetUri()).isNull();
    }

    @ParameterizedTest
    @MethodSource("http가_아닌_주소")
    void JWKS_주소가_http_https_주소가_아니면_예외가_발생한다(String jwkSetUri) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, jwkSetUri, KEYS, null,
                ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.jwk-set-uri는 http 또는 https 주소여야 합니다: " + jwkSetUri);
    }

    static Stream<String> http가_아닌_주소() {
        return Stream.of("member:8080/.well-known/jwks.json", "file:/keys/jwks.json", "http://", "http://member:8080/a b");
    }

    @Test
    void 검증_키의_key_id가_중복되면_예외가_발생한다() {
        // given
        List<JwtProperties.VerificationKey> keys = List.of(
                new JwtProperties.VerificationKey("rs256-v1", PUBLIC_KEY),
                new JwtProperties.VerificationKey("rs256-v1", PUBLIC_KEY));

        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, null, keys, null, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.verification-keys의 key-id가 중복됩니다: rs256-v1");
    }

    @Test
    void 검증_키_목록은_생성_후_바꿀_수_없다() {
        // given
        List<JwtProperties.VerificationKey> keys = new ArrayList<>(KEYS);
        JwtProperties sut = new JwtProperties(ISSUER, AUDIENCE, null, keys, null, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // when
        keys.add(new JwtProperties.VerificationKey("rs256-v2", PUBLIC_KEY));

        // then
        assertThat(sut.verificationKeys()).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("비어_있는_문자열")
    void 검증_키의_key_id가_비어_있으면_예외가_발생한다(String keyId) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties.VerificationKey(keyId, PUBLIC_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.verification-keys[].key-id는 필수입니다.");
    }

    @Test
    void 검증_키의_공개키_위치가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new JwtProperties.VerificationKey("rs256-v1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.verification-keys[].public-key는 필수입니다.");
    }

    @ParameterizedTest
    @MethodSource("유효하지_않은_유효기간")
    void Access_Token_유효기간이_0_이하이거나_없으면_예외가_발생한다(Duration accessExpiration) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, null, KEYS, null, accessExpiration, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.access-token-expiration은 0보다 커야 합니다.");
    }

    static Stream<Duration> 유효하지_않은_유효기간() {
        return Stream.of(Duration.ZERO, Duration.ofMinutes(-1), null);
    }

    @ParameterizedTest
    @MethodSource("유효하지_않은_유효기간")
    void Refresh_Token_유효기간이_0_이하이거나_없으면_예외가_발생한다(Duration refreshExpiration) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, null, KEYS, null, ACCESS_EXPIRATION, refreshExpiration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.refresh-token-expiration은 0보다 커야 합니다.");
    }

    @ParameterizedTest
    @MethodSource("Access_Token보다_길지_않은_유효기간")
    void Refresh_Token_유효기간이_Access_Token보다_길지_않으면_예외가_발생한다(Duration refreshExpiration) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, null, KEYS, null, ACCESS_EXPIRATION, refreshExpiration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.refresh-token-expiration은 jwt.access-token-expiration보다 길어야 합니다.");
    }

    static Stream<Duration> Access_Token보다_길지_않은_유효기간() {
        return Stream.of(ACCESS_EXPIRATION, Duration.ofMinutes(10));
    }

    @Test
    void JWKS_설정이_없으면_기본값을_쓴다() {
        // when
        JwtProperties sut = new JwtProperties(ISSUER, AUDIENCE, null, KEYS, null, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // then
        assertThat(sut.jwkSet()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.jwkSet().cacheTtl()).isEqualTo(Duration.ofMinutes(5));
            softly.assertThat(sut.jwkSet().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            softly.assertThat(sut.jwkSet().readTimeout()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    void JWKS_설정의_일부만_지정하면_나머지는_기본값을_쓴다() {
        // when
        JwtProperties.JwkSet sut = new JwtProperties.JwkSet(Duration.ofMinutes(1), null, null);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.cacheTtl()).isEqualTo(Duration.ofMinutes(1));
            softly.assertThat(sut.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            softly.assertThat(sut.readTimeout()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @ParameterizedTest
    @MethodSource("영_이하의_시간")
    void JWKS_캐시_시간이_0_이하이면_예외가_발생한다(Duration cacheTtl) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties.JwkSet(cacheTtl, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.jwk-set.cache-ttl은 0보다 커야 합니다.");
    }

    static Stream<Duration> 영_이하의_시간() {
        return Stream.of(Duration.ZERO, Duration.ofMinutes(-1));
    }

    @ParameterizedTest
    @MethodSource("영_이하의_시간")
    void JWKS_연결_타임아웃이_0_이하이면_예외가_발생한다(Duration connectTimeout) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties.JwkSet(null, connectTimeout, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.jwk-set.connect-timeout은 0보다 커야 합니다.");
    }

    @ParameterizedTest
    @MethodSource("영_이하의_시간")
    void JWKS_응답_타임아웃이_0_이하이면_예외가_발생한다(Duration readTimeout) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties.JwkSet(null, null, readTimeout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.jwk-set.read-timeout은 0보다 커야 합니다.");
    }

}
