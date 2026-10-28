package com.ok.member.auth.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ok.common.config.JwtConfig;
import com.ok.common.config.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 토큰에 서명하는 {@link JwtEncoder} 빈을 등록한다.
 *
 * <p>검증(디코더)은 모든 모듈이 필요해 common의 {@link JwtConfig}에 있고, 발급은 토큰을 만드는 member만 한다.
 * 알고리즘과 비밀키는 검증 쪽과 어긋나지 않도록 {@link JwtConfig}의 값을 그대로 쓴다.
 * {@link JwtProperties}는 모듈 테스트처럼 common 설정이 로드되지 않는 환경에서도 발급 빈이 뜨도록 여기서도 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtEncoderConfig {

    /**
     * 토큰에 서명하는 인코더.
     *
     * <p>인코더는 토큰 헤더의 알고리즘과 {@code kid}에 맞는 키를 골라 서명한다. 그래서 비밀키에도 같은 {@code kid}를 붙여 둔다.
     * (헤더에만 {@code kid}를 넣고 키에 없으면 "서명할 키를 찾을 수 없다"며 실패한다)
     * 토큰을 발급할 때 JwsHeader에 {@link JwtConfig#SIGNING_ALGORITHM}과 {@code kid}를 반드시 지정해야 한다. → {@code AccessTokenProvider} 참고
     */
    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        OctetSequenceKey signingKey = new OctetSequenceKey.Builder(JwtConfig.secretKey(properties))
                .keyID(properties.keyId())
                .algorithm(JWSAlgorithm.parse(JwtConfig.SIGNING_ALGORITHM.getName()))
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

}
