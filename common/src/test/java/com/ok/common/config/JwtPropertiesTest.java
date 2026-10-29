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
    private static final Duration ACCESS_EXPIRATION = Duration.ofMinutes(30);
    private static final Duration REFRESH_EXPIRATION = Duration.ofDays(14);

    @Test
    void 올바른_설정값이면_생성된다() {
        // when
        JwtProperties sut = new JwtProperties(ISSUER, AUDIENCE, KEYS, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

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
        assertThatThrownBy(() -> new JwtProperties(issuer, AUDIENCE, KEYS, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
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
        assertThatThrownBy(() -> new JwtProperties(ISSUER, audience, KEYS, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.audience는 필수입니다.");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_키_목록")
    void 검증_키가_없으면_예외가_발생한다(List<JwtProperties.VerificationKey> keys) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, keys, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.verification-keys는 하나 이상 필요합니다.");
    }

    static Stream<List<JwtProperties.VerificationKey>> 비어_있는_키_목록() {
        return Stream.of(null, List.of());
    }

    @Test
    void 검증_키의_key_id가_중복되면_예외가_발생한다() {
        // given
        List<JwtProperties.VerificationKey> keys = List.of(
                new JwtProperties.VerificationKey("rs256-v1", PUBLIC_KEY),
                new JwtProperties.VerificationKey("rs256-v1", PUBLIC_KEY));

        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, keys, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.verification-keys의 key-id가 중복됩니다: rs256-v1");
    }

    @Test
    void 검증_키_목록은_생성_후_바꿀_수_없다() {
        // given
        List<JwtProperties.VerificationKey> keys = new ArrayList<>(KEYS);
        JwtProperties sut = new JwtProperties(ISSUER, AUDIENCE, keys, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

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
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, KEYS, accessExpiration, REFRESH_EXPIRATION))
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
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, KEYS, ACCESS_EXPIRATION, refreshExpiration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.refresh-token-expiration은 0보다 커야 합니다.");
    }

    @ParameterizedTest
    @MethodSource("Access_Token보다_길지_않은_유효기간")
    void Refresh_Token_유효기간이_Access_Token보다_길지_않으면_예외가_발생한다(Duration refreshExpiration) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, KEYS, ACCESS_EXPIRATION, refreshExpiration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.refresh-token-expiration은 jwt.access-token-expiration보다 길어야 합니다.");
    }

    static Stream<Duration> Access_Token보다_길지_않은_유효기간() {
        return Stream.of(ACCESS_EXPIRATION, Duration.ofMinutes(10));
    }

}
