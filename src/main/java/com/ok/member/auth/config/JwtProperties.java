package com.ok.member.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * JWT 발급·검증에 필요한 설정값
 *
 * <p>application.yaml의 {@code jwt.*} 값이 이 record에 바인딩된다.
 * <pre>
 * jwt:
 *   issuer: paldo-gotgan
 *   secret: ${JWT_SECRET}
 *   access-token-expiration: 30m
 * </pre>
 *
 * <p><b>비밀키 형식</b>: {@code secret}은 <b>Base64로 인코딩된 문자열</b>이다.
 * 실제 서명 키는 이 값을 Base64 디코딩한 바이트이며, 디코딩한 결과가 32바이트 이상이어야 한다.
 * <pre>
 * # 32바이트 난수를 Base64로 인코딩한 값 생성
 * openssl rand -base64 32
 * </pre>
 * 문자열을 그대로 키로 쓰지 않고 Base64로 통일한 이유:
 * <ul>
 *   <li>openssl로 만든 난수 32바이트가 그대로 키가 되어, 키의 실제 무작위성(엔트로피)이 보장된다.</li>
 *   <li>다른 서비스나 도구가 같은 키로 검증할 때도 "Base64 디코딩한 바이트"라는 하나의 규칙만 따르면 된다.</li>
 * </ul>
 *
 * <p>값이 잘못되면 애플리케이션 기동 시점에 바로 실패하도록 생성자에서 검증한다.
 * 잘못된 설정으로 서버가 떠서 런타임에 토큰 발급이 실패하는 것보다, 기동 자체를 막는 편이 원인을 찾기 쉽다.
 *
 * @param issuer                토큰 발급자(iss claim). 검증 시 이 값과 다른 토큰은 거부한다.
 * @param secret                Base64로 인코딩된 HS256 서명 키. 반드시 환경 변수로 주입하고 저장소에 두지 않는다.
 * @param accessTokenExpiration Access Token 유효 기간. "30m", "1h"처럼 Duration 형식으로 지정한다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        String secret,
        Duration accessTokenExpiration
) {

    /**
     * HS256은 키 길이가 해시 출력 크기(256비트 = 32바이트) 이상이어야 안전하다.
     * Nimbus 라이브러리도 32바이트 미만의 키로는 서명을 거부한다.
     */
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
     *
     * <p>record는 파생 필드를 가질 수 없어 호출할 때마다 디코딩한다.
     * 기동 시 인코더·디코더를 만들 때만 호출되고 요청마다 호출되지 않아 성능 영향은 없다.
     * 또한 매번 새 배열을 반환하므로, 호출한 쪽이 배열을 수정해도 원본 키에 영향을 주지 않는다.
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
