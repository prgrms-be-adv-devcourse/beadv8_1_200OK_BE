package com.ok.member.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class JwtPropertiesTest {

    private static final String ISSUER = "paldo-gotgan";
    /** "test-secret-key-for-jwt-must-be-32-bytes-or-more"(48바이트)를 Base64로 인코딩한 값 */
    private static final String VALID_SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtbXVzdC1iZS0zMi1ieXRlcy1vci1tb3Jl";
    private static final Duration ACCESS_EXPIRATION = Duration.ofMinutes(30);
    private static final Duration REFRESH_EXPIRATION = Duration.ofDays(14);

    @Test
    void 올바른_설정값이면_생성된다() {
        // when
        JwtProperties sut = new JwtProperties(ISSUER, VALID_SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.issuer()).isEqualTo(ISSUER);
            softly.assertThat(sut.accessTokenExpiration()).isEqualTo(ACCESS_EXPIRATION);
            softly.assertThat(sut.refreshTokenExpiration()).isEqualTo(REFRESH_EXPIRATION);
        });
    }

    @Test
    void 서명_키는_비밀키를_Base64_디코딩한_바이트다() {
        // given
        JwtProperties properties = new JwtProperties(ISSUER, VALID_SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // when
        byte[] sut = properties.secretKeyBytes();

        // then
        assertThat(new String(sut, StandardCharsets.UTF_8))
                .isEqualTo("test-secret-key-for-jwt-must-be-32-bytes-or-more");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_문자열")
    void 발급자가_비어_있으면_예외가_발생한다(String issuer) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(issuer, VALID_SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.issuer는 필수입니다.");
    }

    static Stream<String> 비어_있는_문자열() {
        return Stream.of(null, "", " ");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_문자열")
    void 비밀키가_비어_있으면_예외가_발생한다(String secret) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, secret, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.secret은 필수입니다. 환경 변수 JWT_SECRET을 설정하세요.");
    }

    @Test
    void 비밀키가_Base64_형식이_아니면_비밀키를_노출하지_않는_예외가_발생한다() {
        // given
        String secret = "this is not base64!!! this is not base64!!!";

        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, secret, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.secret은 Base64로 인코딩된 문자열이어야 합니다. (openssl rand -base64 32로 생성)");
    }

    @Test
    void 디코딩한_비밀키가_32바이트_미만이면_예외가_발생한다() {
        // given
        String secret = base64OfBytes(31);

        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, secret, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.secret은 Base64 디코딩한 값이 32바이트 이상이어야 합니다. (openssl rand -base64 32로 생성)");
    }

    @Test
    void 디코딩한_비밀키가_정확히_32바이트면_생성된다() {
        // given
        String secret = base64OfBytes(32);

        // when
        JwtProperties sut = new JwtProperties(ISSUER, secret, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // then
        assertThat(sut.secretKeyBytes()).hasSize(32);
    }

    @ParameterizedTest
    @MethodSource("유효하지_않은_유효기간")
    void Access_Token_유효기간이_0_이하이거나_없으면_예외가_발생한다(Duration accessExpiration) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, VALID_SECRET, accessExpiration, REFRESH_EXPIRATION))
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
        assertThatThrownBy(() -> new JwtProperties(ISSUER, VALID_SECRET, ACCESS_EXPIRATION, refreshExpiration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.refresh-token-expiration은 0보다 커야 합니다.");
    }

    @ParameterizedTest
    @MethodSource("Access_Token보다_길지_않은_유효기간")
    void Refresh_Token_유효기간이_Access_Token보다_길지_않으면_예외가_발생한다(Duration refreshExpiration) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, VALID_SECRET, ACCESS_EXPIRATION, refreshExpiration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.refresh-token-expiration은 jwt.access-token-expiration보다 길어야 합니다.");
    }

    static Stream<Duration> Access_Token보다_길지_않은_유효기간() {
        return Stream.of(ACCESS_EXPIRATION, Duration.ofMinutes(10));
    }

    @Test
    void toString에_비밀키가_노출되지_않는다() {
        // given
        JwtProperties properties = new JwtProperties(ISSUER, VALID_SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // when
        String sut = properties.toString();

        // then
        assertThat(sut).doesNotContain(VALID_SECRET).contains("secret=****");
    }

    /** 지정한 바이트 수의 값을 Base64로 인코딩한다. (경계값 테스트용) */
    private static String base64OfBytes(int length) {
        return Base64.getEncoder().encodeToString("a".repeat(length).getBytes(StandardCharsets.UTF_8));
    }

}
