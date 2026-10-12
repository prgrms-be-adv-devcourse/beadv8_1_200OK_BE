package com.ok.member.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

public class JwtPropertiesTest {

    private static final String ISSUER = "paldo-gotgan";
    /** 32바이트 이상인 테스트용 비밀키 */
    private static final String VALID_SECRET = "test-secret-key-for-jwt-must-be-32-bytes-or-more";
    private static final Duration EXPIRATION = Duration.ofMinutes(30);

    @Test
    @DisplayName("올바른 설정값이면 생성된다")
    void create_valid() {
        // when & then
        assertThatCode(() -> new JwtProperties(ISSUER, VALID_SECRET, EXPIRATION))
                .doesNotThrowAnyException();
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
    @DisplayName("비밀키가 32바이트 미만이면 예외가 발생한다")
    void create_shortSecret() {
        // given
        String secret = "a".repeat(31);

        // when & then
        assertThatThrownBy(() -> new JwtProperties(ISSUER, secret, EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("비밀키가 정확히 32바이트면 생성된다")
    void create_secretBoundary() {
        // given
        String secret = "a".repeat(32);

        // when & then
        assertThatCode(() -> new JwtProperties(ISSUER, secret, EXPIRATION))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("만료 시간이 0 이하면 예외가 발생한다")
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

}
