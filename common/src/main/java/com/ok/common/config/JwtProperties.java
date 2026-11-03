package com.ok.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JWT 검증과 토큰 유효 기간에 필요한 설정값
 *
 * <p>application.yaml의 {@code jwt.*} 값이 이 record에 바인딩된다.
 * <pre>
 * jwt:
 *   issuer: paldo-gotgan
 *   audience: paldo-gotgan-api
 *   verification-keys:
 *     - key-id: rs256-v1
 *       public-key: file:keys/rs256-v1-public.pem
 *   access-token-expiration: 30m
 *   refresh-token-expiration: 14d
 * </pre>
 *
 * <p>검증에는 공개키만 쓴다. 서명용 개인키({@code jwt.signing.*})는 토큰을 발급하는 모듈(member의 auth)만
 * 자기 설정으로 갖고, 여기에는 두지 않는다. 개인키가 없는 앱도 이 설정만으로 기동해 토큰을 검증할 수 있어야 하기 때문이다.
 *
 * <p>값이 잘못되면 애플리케이션 기동 시점에 바로 실패하도록 생성자에서 검증한다.
 * 잘못된 설정으로 서버가 떠서 런타임에 토큰 검증이 실패하는 것보다, 기동 자체를 막는 편이 원인을 찾기 쉽다.
 *
 * @param issuer                 토큰 발급자(iss claim). 검증 시 이 값과 다른 토큰은 거부한다.
 * @param audience               토큰을 받는 서비스(aud claim). 검증 시 이 값이 없는 토큰은 거부한다.
 *                               지금은 발급·검증이 한 애플리케이션이라 값이 하나지만, 서비스가 분리되면 서비스마다 다른 값을 쓴다.
 * @param verificationKeys       검증에 쓰는 공개키 목록. 토큰 헤더의 {@code kid}로 키를 고르고, 목록에 없는 {@code kid}는 거부한다.
 *                               키를 교체할 때 옛 키와 새 키를 함께 두어, 교체 전에 발급된 토큰도 만료될 때까지 받아들인다.
 * @param accessTokenExpiration  Access Token 유효 기간. "30m", "1h"처럼 Duration 형식으로 지정한다.
 * @param refreshTokenExpiration Refresh Token 유효 기간. "14d"처럼 Duration 형식으로 지정한다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        String audience,
        List<VerificationKey> verificationKeys,
        Duration accessTokenExpiration,
        Duration refreshTokenExpiration
) {

    /**
     * record의 compact 생성자. 설정 바인딩 직후 실행되어 값의 유효성을 검사한다.
     */
    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("jwt.issuer는 필수입니다.");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("jwt.audience는 필수입니다.");
        }
        if (verificationKeys == null || verificationKeys.isEmpty()) {
            throw new IllegalArgumentException("jwt.verification-keys는 하나 이상 필요합니다.");
        }
        // 같은 kid가 둘이면 어느 공개키로 검증할지 정할 수 없다
        Set<String> keyIds = new HashSet<>();
        for (VerificationKey key : verificationKeys) {
            if (!keyIds.add(key.keyId())) {
                throw new IllegalArgumentException(
                        "jwt.verification-keys의 key-id가 중복됩니다: %s".formatted(key.keyId()));
            }
        }
        verificationKeys = List.copyOf(verificationKeys);
        requirePositive(accessTokenExpiration, "jwt.access-token-expiration");
        requirePositive(refreshTokenExpiration, "jwt.refresh-token-expiration");
        // Refresh Token은 Access Token을 다시 발급받기 위한 토큰이므로, 더 오래 유효해야 의미가 있다.
        if (refreshTokenExpiration.compareTo(accessTokenExpiration) <= 0) {
            throw new IllegalArgumentException(
                    "jwt.refresh-token-expiration은 jwt.access-token-expiration보다 길어야 합니다.");
        }
    }

    /**
     * 유효 기간이 지정되어 있고 0보다 큰지 검사한다.
     */
    private static void requirePositive(Duration duration, String propertyName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + "은 0보다 커야 합니다.");
        }
    }

    /**
     * 검증용 공개키 하나.
     *
     * @param keyId     서명 키 식별자(kid header). 토큰 헤더에 그대로 노출되므로 키 내용이나 추측 가능한 정보를 넣지 않는다.
     * @param publicKey X.509 PEM 공개키 위치. {@code file:} 또는 {@code classpath:}로 지정한다. ({@link RsaPemKeys} 참고)
     */
    public record VerificationKey(String keyId, Resource publicKey) {

        public VerificationKey {
            if (keyId == null || keyId.isBlank()) {
                throw new IllegalArgumentException("jwt.verification-keys[].key-id는 필수입니다.");
            }
            if (publicKey == null) {
                throw new IllegalArgumentException("jwt.verification-keys[].public-key는 필수입니다.");
            }
        }

    }

}
