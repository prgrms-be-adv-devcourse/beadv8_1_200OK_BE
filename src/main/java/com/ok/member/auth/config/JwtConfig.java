package com.ok.member.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * JWT 서명(발급)과 검증에 사용하는 빈을 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /**
     * 토큰에 서명하는 인코더.
     */
    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    /**
     * 토큰을 검증하는 디코더.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // 기본 검증(만료 시간 등)에 발급자(iss) 검증을 추가한다.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * 설정의 비밀키 문자열을 HMAC-SHA256용 SecretKey로 변환한다.
     * 인코더와 디코더가 반드시 같은 키를 쓰도록 한 곳에서 만든다.
     */
    private static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secretKeyBytes(), "HmacSHA256");
    }

}
