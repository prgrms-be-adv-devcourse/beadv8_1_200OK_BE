package com.ok.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;

/**
 * PEM 파일에서 JWT 서명·검증용 RSA 키를 읽는다.
 *
 * <p>검증(common의 {@link JwtConfig}), 발급(member의 {@code JwtEncoderConfig}), 테스트 토큰({@code TestAccessTokens})이
 * 같은 형식 규칙으로 키를 읽도록 한 곳에 둔다.
 *
 * <p>형식은 {@code openssl genpkey}가 만드는 그대로다. 개인키는 PKCS#8({@code -----BEGIN PRIVATE KEY-----}),
 * 공개키는 X.509({@code -----BEGIN PUBLIC KEY-----})이다. PKCS#1({@code -----BEGIN RSA PRIVATE KEY-----})은 읽지 않는다.
 *
 * <p>예외 메시지에는 키 내용이 아니라 파일 위치만 넣는다. (기동 실패 로그로 개인키가 유출되는 것을 막기 위해)
 */
public final class RsaPemKeys {

    /**
     * RS256에 쓸 RSA 키의 최소 길이. 2048비트 미만은 안전하지 않다고 보아(NIST SP 800-57) Nimbus도 서명을 거부한다.
     * 검증 키도 같은 기준으로 막아, 짧은 키가 설정되면 발급 시점이 아니라 기동 시점에 실패하게 한다.
     */
    static final int MIN_KEY_BITS = 2048;

    /** 키 오류 메시지 끝에 붙이는 안내. 테스트가 전체 메시지를 비교할 수 있도록 패키지 범위로 둔다. */
    static final String KEY_GUIDE = "키 생성 방법은 docs/DEVELOPMENT.md의 '8. 인증 (JWT)'를 참고하세요.";

    private RsaPemKeys() {
    }

    /**
     * X.509 PEM 공개키를 읽는다.
     *
     * @throws IllegalStateException 파일을 읽을 수 없거나, 형식이 다르거나, 키가 2048비트 미만인 경우
     */
    public static RSAPublicKey publicKey(Resource resource) {
        return read(resource, RsaKeyConverters.x509(), "X.509 공개키(-----BEGIN PUBLIC KEY-----)");
    }

    /**
     * PKCS#8 PEM 개인키를 읽는다.
     *
     * @throws IllegalStateException 파일을 읽을 수 없거나, 형식이 다르거나, 키가 2048비트 미만인 경우
     */
    public static RSAPrivateKey privateKey(Resource resource) {
        return read(resource, RsaKeyConverters.pkcs8(), "PKCS#8 개인키(-----BEGIN PRIVATE KEY-----)");
    }

    private static <K extends RSAKey> K read(Resource resource, Converter<InputStream, K> converter, String format) {
        K key;
        try (InputStream in = resource.getInputStream()) {
            key = converter.convert(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "JWT 키 파일을 읽을 수 없습니다: %s. %s".formatted(resource.getDescription(), KEY_GUIDE), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "JWT 키 파일이 %s 형식이 아닙니다: %s. %s".formatted(format, resource.getDescription(), KEY_GUIDE), e);
        }
        if (key.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalStateException("JWT 키는 %d비트 이상이어야 합니다: %s. %s"
                    .formatted(MIN_KEY_BITS, resource.getDescription(), KEY_GUIDE));
        }
        return key;
    }

}
