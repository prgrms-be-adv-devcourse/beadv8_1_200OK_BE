package com.ok.member.auth.config;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ok.common.config.JwtConfig;
import com.ok.common.config.JwtProperties;
import com.ok.common.config.RsaPemKeys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.interfaces.RSAPrivateKey;

/**
 * 토큰에 서명하는 {@link JwtEncoder} 빈을 등록한다.
 *
 * <p>검증(디코더)은 모든 모듈이 필요해 common의 {@link JwtConfig}에 있고, 발급은 토큰을 만드는 member만 한다.
 * 개인키({@link JwtSigningProperties})는 이 모듈만 갖고, 공개키·{@code kid}·알고리즘은 검증 쪽과 어긋나지 않도록
 * {@link JwtConfig}가 만든 값을 그대로 쓴다.
 * {@link JwtProperties}는 모듈 테스트처럼 common 설정이 로드되지 않는 환경에서도 발급 빈이 뜨도록 여기서도 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, JwtSigningProperties.class})
public class JwtEncoderConfig {

    /**
     * 토큰에 서명하는 인코더.
     *
     * <p>인코더는 토큰 헤더의 알고리즘과 {@code kid}에 맞는 키를 골라 서명한다. 그래서 서명 키에도 같은 {@code kid}를 붙여 둔다.
     * (헤더에만 {@code kid}를 넣고 키에 없으면 "서명할 키를 찾을 수 없다"며 실패한다)
     * 토큰을 발급할 때 JwsHeader에 {@link JwtConfig#SIGNING_ALGORITHM}과 {@code kid}를 반드시 지정해야 한다. → {@code AccessTokenProvider} 참고
     *
     * @throws IllegalStateException 서명 {@code kid}의 공개키가 검증 목록에 없거나, 개인키가 그 공개키와 짝이 아닌 경우
     */
    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties, JwtSigningProperties signing) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey(properties, signing))));
    }

    /**
     * 검증 목록의 공개키에 개인키를 붙여 서명 키를 만든다.
     * 공개키를 검증 목록에서 가져오고 짝을 확인하는 이유: 우리 디코더가 검증하지 못하는 토큰을 발급하면
     * 로그인은 성공하는데 모든 API가 401이 된다. 잘못된 키 설정을 발급 시점이 아니라 기동 시점에 잡는다.
     */
    private static RSAKey signingKey(JwtProperties properties, JwtSigningProperties signing) {
        JWK verificationKey = JwtConfig.verificationKeys(properties).getKeyByKeyId(signing.keyId());
        if (verificationKey == null) {
            throw new IllegalStateException(
                    "jwt.signing.key-id(%s)의 공개키가 jwt.verification-keys에 없습니다.".formatted(signing.keyId()));
        }
        RSAKey publicKey = verificationKey.toRSAKey();
        RSAPrivateKey privateKey = RsaPemKeys.privateKey(signing.privateKey());
        // 같은 키 쌍이면 modulus(n)가 같다
        if (!publicKey.getModulus().decodeToBigInteger().equals(privateKey.getModulus())) {
            throw new IllegalStateException(
                    "jwt.signing.private-key가 kid(%s)의 공개키와 짝이 아닙니다.".formatted(signing.keyId()));
        }
        return new RSAKey.Builder(publicKey).privateKey(privateKey).build();
    }

}
