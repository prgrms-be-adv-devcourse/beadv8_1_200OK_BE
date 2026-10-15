package com.ok.member.auth.config;

import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * JWT 서명(발급)과 검증에 사용하는 빈을 등록한다.
 *
 * <p>JWT를 직접 파싱하거나 서명하는 코드를 만들지 않고, Spring Security가 제공하는
 * {@link JwtEncoder} / {@link JwtDecoder}(내부 구현: Nimbus JOSE + JWT)를 사용한다.
 *
 * <p>대칭키 방식(HS256)을 사용한다. 발급과 검증을 같은 애플리케이션에서 하므로
 * 공개키/개인키 쌍(RS256)을 관리할 필요가 없다. 나중에 다른 서비스가 토큰을 검증해야 하는 구조가 되면
 * 개인키를 공유하지 않아도 되는 RS256으로 바꾸는 것을 고려한다.
 *
 * <p>추후 과제: 키 로테이션을 위한 kid(Key ID) 헤더는 아직 지원하지 않는다.
 * 지금은 키를 교체하면 기존 토큰이 모두 무효가 된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /**
     * 서명 알고리즘(JWS 기준 이름: HS256). 발급(AccessTokenProvider)과 검증(jwtDecoder)이 같은 값을 쓰도록 한 곳에서 정한다.
     */
    public static final MacAlgorithm SIGNING_ALGORITHM = MacAlgorithm.HS256;

    /**
     * 같은 알고리즘의 Java(JCA) 표준 이름. SecretKeySpec은 JWS 이름("HS256")이 아니라 이 이름을 사용한다.
     * MacAlgorithm.HS256.getName()은 "HS256"을 반환하므로 SecretKeySpec에 그대로 쓸 수 없다.
     */
    private static final String JCA_ALGORITHM = "HmacSHA256";

    /**
     * 만료 시간 허용 오차. 기본값은 60초지만, 발급과 검증을 같은 애플리케이션(NTP로 시간 동기화된 서버)에서 하므로
     * 서버 간 시계 차이를 보정할 필요가 없다. 설정한 유효 기간(30분)이 정확히 지켜지도록 0으로 둔다.
     */
    private static final Duration CLOCK_SKEW = Duration.ZERO;

    /**
     * 토큰에 서명하는 인코더.
     *
     * <p>주의: 대칭키로 만든 인코더는 서명 알고리즘을 스스로 정하지 않는다.
     * 토큰을 발급할 때 JwsHeader에 {@link #SIGNING_ALGORITHM}을 반드시 지정해야 한다.
     * (지정하지 않으면 기본값 RS256으로 서명할 키를 찾다가 실패한다.) → {@code AccessTokenProvider} 참고
     */
    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    /**
     * 토큰을 검증하는 디코더.
     *
     * <p>검증 항목
     * <ul>
     *   <li>서명: 같은 비밀키, HS256 알고리즘으로 서명되었는지 (위조·변조된 토큰 거부)</li>
     *   <li>만료(exp), 활성 시작(nbf): 허용 오차 없이 검증 ({@link #CLOCK_SKEW})</li>
     *   <li>발급자(iss): 우리 서비스가 발급한 토큰인지</li>
     * </ul>
     *
     * <p>지금은 테스트에서만 사용하고, 이후 인가 규칙 적용 이슈에서
     * SecurityFilterChain의 oauth2ResourceServer 설정이 이 빈을 사용해 요청마다 토큰을 검증한다.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(SIGNING_ALGORITHM)
                .build();

        // JwtValidators.createDefaultWithIssuer()는 허용 오차 60초가 고정이라, 검증기를 직접 조합한다.
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                // 만료를 위조·형식 오류와 구분할 수 있도록 별도 오류 코드를 남긴다 (오류 응답에서 token-expired로 구분)
                new ExpiredTokenValidator(),
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(properties.issuer())
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * 설정의 비밀키(Base64 문자열)를 디코딩해 HMAC-SHA256용 SecretKey로 변환한다.
     * 인코더와 디코더가 반드시 같은 키를 쓰도록 한 곳에서 만든다.
     */
    private static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secretKeyBytes(), JCA_ALGORITHM);
    }

}
