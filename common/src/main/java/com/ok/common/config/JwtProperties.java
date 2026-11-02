package com.ok.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.net.URI;
import java.net.URISyntaxException;
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
 *   jwk-set-uri: http://member:8080/.well-known/jwks.json   # 없으면 아래 로컬 공개키로 검증한다
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
 * @param jwkSetUri              발급 모듈의 JWKS 주소. 지정하면 공개키를 이 주소에서 받아 검증하고 {@code verificationKeys}는 읽지 않는다.
 *                               발급 모듈이 아닌 앱을 따로 배포할 때 키 파일 없이 검증하기 위해서다. 비어 있으면 {@code verificationKeys}를 쓴다.
 *                               서명 키를 가진 발급 모듈은 지정할 수 없다 (자기 자신을 HTTP로 부르게 된다).
 * @param verificationKeys       검증에 쓰는 공개키 목록. {@code jwkSetUri}가 없을 때 필수다. 토큰 헤더의 {@code kid}로 키를 고르고, 목록에 없는 {@code kid}는 거부한다.
 *                               키를 교체할 때 옛 키와 새 키를 함께 두어, 교체 전에 발급된 토큰도 만료될 때까지 받아들인다.
 *                               발급 모듈은 이 목록 전체를 JWKS({@code /.well-known/jwks.json})로 공개한다.
 * @param jwkSet                 JWKS 캐시 설정. 지정하지 않으면 기본값을 쓴다. ({@link JwkSet} 참고)
 * @param accessTokenExpiration  Access Token 유효 기간. "30m", "1h"처럼 Duration 형식으로 지정한다.
 * @param refreshTokenExpiration Refresh Token 유효 기간. "14d"처럼 Duration 형식으로 지정한다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String jwkSetUri,
        List<VerificationKey> verificationKeys,
        JwkSet jwkSet,
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
        // 환경변수가 없을 때 yaml의 ${JWT_JWK_SET_URI:}는 빈 문자열이 된다. 지정하지 않은 것으로 본다
        if (jwkSetUri != null && jwkSetUri.isBlank()) {
            jwkSetUri = null;
        }
        if (jwkSetUri != null) {
            requireHttpUri(jwkSetUri);
        }
        // 원격 JWKS를 쓰면 로컬 공개키는 읽지 않는다. 모듈 yaml에는 로컬 기본값이 늘 있으므로 함께 있어도 실패시키지 않는다
        if (verificationKeys == null) {
            verificationKeys = List.of();
        }
        if (jwkSetUri == null && verificationKeys.isEmpty()) {
            throw new IllegalArgumentException("jwt.jwk-set-uri가 없으면 jwt.verification-keys가 하나 이상 필요합니다.");
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
        // yaml에 jwt.jwk-set이 없으면 바인딩 결과가 null이다. 모든 앱의 yaml에 같은 기본값을 반복해 적지 않게 한다
        if (jwkSet == null) {
            jwkSet = new JwkSet(null, null, null);
        }
        requirePositive(accessTokenExpiration, "jwt.access-token-expiration");
        requirePositive(refreshTokenExpiration, "jwt.refresh-token-expiration");
        // Refresh Token은 Access Token을 다시 발급받기 위한 토큰이므로, 더 오래 유효해야 의미가 있다.
        if (refreshTokenExpiration.compareTo(accessTokenExpiration) <= 0) {
            throw new IllegalArgumentException(
                    "jwt.refresh-token-expiration은 jwt.access-token-expiration보다 길어야 합니다.");
        }
    }

    /**
     * 원격 JWKS 주소가 http(s) 절대 주소인지 검사한다. 오타가 첫 요청이 아니라 기동 시점에 드러나게 한다.
     */
    private static void requireHttpUri(String jwkSetUri) {
        URI uri;
        try {
            uri = new URI(jwkSetUri);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("jwt.jwk-set-uri는 http 또는 https 주소여야 합니다: " + jwkSetUri, e);
        }
        boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        if (!http || uri.getHost() == null) {
            throw new IllegalArgumentException("jwt.jwk-set-uri는 http 또는 https 주소여야 합니다: " + jwkSetUri);
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
     * JWKS 캐시와 원격 조회 설정. 지정하지 않은 값은 기본값을 쓴다.
     *
     * @param cacheTtl       공개키 목록을 캐시해도 되는 시간. 발급 모듈은 JWKS 응답의 {@code Cache-Control: max-age}로 알린다.
     *                       길수록 발급 모듈이 잠깐 멈춰도 검증이 이어지지만, 키 교체 때 새 키를 공개한 뒤 서명 키를 바꾸기까지 이만큼 기다린다.
     *                       기본값 5분.
     * @param connectTimeout 원격 JWKS 연결 타임아웃. 캐시가 끝난 뒤 첫 요청은 이만큼 기다릴 수 있다. 기본값 2초.
     * @param readTimeout    원격 JWKS 응답 타임아웃. 기본값 2초.
     */
    public record JwkSet(Duration cacheTtl, Duration connectTimeout, Duration readTimeout) {

        private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);
        // 같은 서버 안 컨테이너 간 호출에는 넉넉하고, 발급 모듈이 죽었을 때 사용자가 기다리는 시간은 짧게 끊는 값
        private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

        public JwkSet {
            if (cacheTtl == null) {
                cacheTtl = DEFAULT_CACHE_TTL;
            }
            if (connectTimeout == null) {
                connectTimeout = DEFAULT_TIMEOUT;
            }
            if (readTimeout == null) {
                readTimeout = DEFAULT_TIMEOUT;
            }
            requirePositive(cacheTtl, "jwt.jwk-set.cache-ttl");
            requirePositive(connectTimeout, "jwt.jwk-set.connect-timeout");
            requirePositive(readTimeout, "jwt.jwk-set.read-timeout");
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
