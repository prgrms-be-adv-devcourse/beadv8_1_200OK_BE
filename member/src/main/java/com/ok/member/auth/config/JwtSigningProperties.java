package com.ok.member.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Access Token 서명에 쓰는 개인키 설정값
 *
 * <p>application.yaml의 {@code jwt.signing.*} 값이 이 record에 바인딩된다.
 * <pre>
 * jwt:
 *   signing:
 *     key-id: rs256-v1
 *     private-key: file:keys/rs256-v1-private.pem
 * </pre>
 *
 * <p>검증용 설정({@code JwtProperties})과 나눈 이유: 개인키는 토큰을 발급하는 이 모듈만 가져야 한다.
 * 같은 record에 두면 검증만 하는 앱도 개인키를 필수로 요구받아, 개인키를 모든 앱에 나눠주게 된다.
 *
 * @param keyId      서명에 쓰는 키의 식별자(kid header). {@code jwt.verification-keys}에 같은 {@code kid}의 공개키가 있어야 한다.
 *                   발급한 토큰을 우리 디코더가 검증할 수 있음을 기동 시점에 보장하기 위해서다. ({@link JwtEncoderConfig} 참고)
 * @param privateKey PKCS#8 PEM 개인키 위치. {@code file:} 또는 {@code classpath:}로 지정한다. 저장소에 두지 않는다.
 */
@ConfigurationProperties(prefix = "jwt.signing")
public record JwtSigningProperties(
        String keyId,
        Resource privateKey
) {

    /**
     * record의 compact 생성자. 설정 바인딩 직후 실행되어 값이 비어 있으면 기동을 막는다.
     */
    public JwtSigningProperties {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("jwt.signing.key-id는 필수입니다.");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("jwt.signing.private-key는 필수입니다.");
        }
    }

}
