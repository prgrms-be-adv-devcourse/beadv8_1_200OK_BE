package com.ok.member.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT 발급·검증에 필요한 설정값
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        String secret,
        Duration accessTokenExpiration
) {

    static final int MIN_SECRET_BYTES = 32;

    /**
     * record의 compact 생성자. 설정 바인딩 직후 실행되어 값의 유효성을 검사한다.
     */
    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("jwt.issuer는 필수입니다.");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("jwt.secret은 필수입니다. 환경 변수 JWT_SECRET을 설정하세요.");
        }
        // 문자 수가 아니라 실제 바이트 수로 검사한다. (한글 등 멀티바이트 문자는 1글자가 여러 바이트)
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt.secret은 %d바이트 이상이어야 합니다.".formatted(MIN_SECRET_BYTES));
        }
        if (accessTokenExpiration == null || accessTokenExpiration.isZero() || accessTokenExpiration.isNegative()) {
            throw new IllegalArgumentException("jwt.access-token-expiration은 0보다 커야 합니다.");
        }
    }

    /**
     * 로그나 예외 메시지에 비밀키가 노출되지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "JwtProperties[issuer=%s, secret=****, accessTokenExpiration=%s]"
                .formatted(issuer, accessTokenExpiration);
    }

}
