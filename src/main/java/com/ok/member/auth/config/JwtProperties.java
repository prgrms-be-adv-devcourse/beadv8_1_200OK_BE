package com.ok.member.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

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
        // 디코딩한 실제 키 길이로 검사한다. (Base64 문자열 길이가 아님)
        if (decode(secret).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "jwt.secret은 Base64 디코딩한 값이 %d바이트 이상이어야 합니다. (openssl rand -base64 32로 생성)"
                            .formatted(MIN_SECRET_BYTES));
        }
        if (accessTokenExpiration == null || accessTokenExpiration.isZero() || accessTokenExpiration.isNegative()) {
            throw new IllegalArgumentException("jwt.access-token-expiration은 0보다 커야 합니다.");
        }
    }

    /**
     * 서명에 사용할 실제 키 바이트를 반환한다. (secret을 Base64 디코딩한 값)
     */
    public byte[] secretKeyBytes() {
        return decode(secret);
    }

    /**
     * Base64 문자열을 디코딩한다. 형식이 잘못되면 설정 오류로 처리한다.
     * 예외 메시지에 비밀키 값을 포함하지 않는다. (기동 실패 로그로 키가 유출되는 것을 막기 위해)
     */
    private static byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "jwt.secret은 Base64로 인코딩된 문자열이어야 합니다. (openssl rand -base64 32로 생성)");
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
