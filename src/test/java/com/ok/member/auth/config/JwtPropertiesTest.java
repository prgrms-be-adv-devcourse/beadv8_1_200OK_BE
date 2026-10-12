package com.ok.member.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

public class JwtPropertiesTest {

    private static final String ISSUER = "paldo-gotgan";
    /** 32바이트 이상인 테스트용 비밀키 */
    private static final String VALID_SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtbXVzdC1iZS0zMi1ieXRlcy1vci1tb3Jl";
    private static final Duration EXPIRATION = Duration.ofMinutes(30);

    @Test
    @DisplayName("올바른 설정값이면 생성된다")
    void create_valid() {
        // when & then
        assertThatCode(() -> new JwtProperties(ISSUER, VALID_SECRET, EXPIRATION))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("서명 키는 비밀키를 Base64 디코딩한 바이트다")
    void secretKeyBytes_decodesBase64() {
        // given
        JwtProperties properties = new JwtProperties(ISSUER, VALID_SECRET, EXPIRATION);

        // when
        byte[] keyBytes = properties.secretKeyBytes();

        // then
        assertThat(new String(keyBytes, StandardCharsets.UTF_8))
                .isEqualTo("test-secret-key-for-jwt-must-be-32-bytes-or-more");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("발급자가 비어 있으면 예외가 발생한다")
    void create_blankIssuer(String issuer) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(issuer, VALID_SECRET, EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.issuer");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("비밀키가 비어 있으면 예외가 발생한다")
    void create_blankSecret(String secret) {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, secret, EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    @DisplayName("비밀키가 Base64 형식이 아니면 예외가 발생하고, 메시지에 비밀키 값이 포함되지 않는다")
    void create_notBase64() {
        // given
        String secret = "this is not base64!!! this is not base64!!!";

        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, secret, EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base64")
                .message().doesNotContain(secret);
    }

    @Test
    @DisplayName("디코딩한 비밀키가 32바이트 미만이면 예외가 발생한다")
    void create_shortSecret() {
        // given
        String secret = base64OfBytes(31);

        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, secret, EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("디코딩한 비밀키가 정확히 32바이트면 생성된다")
    void create_secretBoundary() {
        // given
        String secret = base64OfBytes(32);

        // when & then
        assertThatCode(() -> new JwtProperties(ISSUER, secret, EXPIRATION))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("만료 시간이 0 이하이거나 없으면 예외가 발생한다")
    void create_invalidExpiration() {
        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, VALID_SECRET, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties(ISSUER, VALID_SECRET, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties(ISSUER, VALID_SECRET, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString에 비밀키가 노출되지 않는다")
    void toString_masksSecret() {
        // given
        JwtProperties properties = new JwtProperties(ISSUER, VALID_SECRET, EXPIRATION);

        // when
        String result = properties.toString();

        // then
        assertThat(result).doesNotContain(VALID_SECRET);
        assertThat(result).contains("secret=****");
    }

    /** 지정한 바이트 수의 값을 Base64로 인코딩한다. (경계값 테스트용) */
    private static String base64OfBytes(int length) {
        return Base64.getEncoder().encodeToString("a".repeat(length).getBytes(StandardCharsets.UTF_8));
    }

}
