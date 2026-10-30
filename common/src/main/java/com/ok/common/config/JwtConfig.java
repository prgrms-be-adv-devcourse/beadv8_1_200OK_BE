package com.ok.common.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * JWT 검증에 사용하는 빈과 발급·검증이 함께 쓰는 값(알고리즘, 토큰 타입, 공개키)을 둔다.
 *
 * <p>검증({@link JwtDecoder})은 모든 모듈 앱이 보안 필터를 쓰기 위해 필요하므로 common에 둔다.
 * 발급({@code JwtEncoder})은 토큰을 만드는 member 모듈의 {@code JwtEncoderConfig}가 맡는다.
 *
 * <p>JWT를 직접 파싱하거나 서명하는 코드를 만들지 않고, Spring Security가 제공하는
 * {@code JwtEncoder} / {@link JwtDecoder}(내부 구현: Nimbus JOSE + JWT)를 사용한다.
 *
 * <p>비대칭키 방식(RS256)을 사용한다. 모듈마다 앱이 따로 떠서 토큰을 검증하는 곳이 여럿이므로,
 * 검증하는 앱에는 공개키만 두고 개인키는 발급하는 앱만 갖게 한다. 대칭키(HS256)는 검증 키로 서명도 할 수 있어서,
 * 검증만 하는 앱도 아무 회원·권한의 토큰을 만들 수 있었다. 서명 알고리즘과 키는 이 클래스와 {@code JwtEncoderConfig}에서만
 * 관리하므로 각 모듈의 인가 코드는 알고리즘과 무관하다.
 *
 * <p>토큰 헤더의 {@code kid}로 검증할 공개키를 고른다. 키를 교체할 때 옛 키와 새 키를 함께 등록해,
 * 교체 전에 발급된 토큰도 만료될 때까지 받아들인다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /**
     * 서명 알고리즘(RS256). 발급(AccessTokenProvider)과 검증(jwtDecoder)이 같은 값을 쓰도록 한 곳에서 정한다.
     */
    public static final SignatureAlgorithm SIGNING_ALGORITHM = SignatureAlgorithm.RS256;

    /**
     * Access Token의 타입(typ 헤더). RFC 9068이 정한 Access Token용 값이다.
     * 용도가 다른 JWT가 생겨도 Access Token으로 쓰이지 않도록, 검증 시 이 타입만 받아들인다. (RFC 8725 권고)
     */
    public static final String TOKEN_TYPE = "at+jwt";

    /**
     * 같은 알고리즘의 Nimbus 표현. JWK에 알고리즘을 적어, 그 키가 RS256에만 쓰이게 한다.
     */
    private static final JWSAlgorithm JWS_ALGORITHM = JWSAlgorithm.parse(SIGNING_ALGORITHM.getName());

    /**
     * 만료 시간 허용 오차. 기본값은 60초지만, 설정한 유효 기간(30분)이 정확히 지켜지도록 0으로 둔다.
     * 발급(member)과 검증(각 모듈 앱)이 다른 서버로 나뉘어도 0을 유지한다. 검증 서버의 시계가 빠르면 토큰이 그만큼 일찍
     * 만료될 뿐이고 클라이언트는 Refresh Token으로 재발급받는다. 큰 시계 차이는 허용 오차가 아니라 서버 시간 동기화(NTP)로 막는다.
     * 만료는 {@link ExpiredTokenValidator} 하나가 이 값으로 판단한다.
     */
    private static final Duration CLOCK_SKEW = Duration.ZERO;

    /**
     * kid가 없는 토큰을 거부하는 검증기. 우리가 발급하는 토큰에는 항상 kid가 있다.
     * kid가 없으면 Nimbus가 검증 목록의 모든 키로 서명을 맞춰 보므로, "kid로 검증할 공개키를 고른다"는 규칙이
     * kid 없는 토큰에도 적용되도록 막는다.
     */
    private static final OAuth2TokenValidator<Jwt> KEY_ID_REQUIRED = jwt -> jwt.getHeaders().get(JoseHeaderNames.KID) != null
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The kid header is missing", null));

    /**
     * 토큰을 검증하는 디코더. 보안 필터({@code SecurityConfig}의 oauth2ResourceServer)가 요청마다 이 빈으로 토큰을 검증한다.
     *
     * <p>검증 항목
     * <ul>
     *   <li>서명: 헤더의 {@code kid}에 해당하는 공개키로 RS256 서명이 맞는지 (위조·변조된 토큰, {@code kid}가 없거나 등록되지 않은 {@code kid} 거부)</li>
     *   <li>타입(typ): Access Token({@link #TOKEN_TYPE})인지</li>
     *   <li>만료(exp): 필수이며, 허용 오차 없이 검증 ({@link #CLOCK_SKEW})</li>
     *   <li>발급자(iss): 우리 서비스가 발급한 토큰인지</li>
     *   <li>대상(aud): 이 서비스를 위한 토큰인지</li>
     * </ul>
     *
     * <p>활성 시작(nbf)은 우리가 발급하는 토큰에 넣지 않으므로 검증하지 않는다.
     * nbf를 발급하게 되면 {@code JwtTimestampValidator}를 검증기 목록에 다시 추가한다.
     *
     * <p>알고리즘은 RS256 하나로 고정한다. 헤더의 {@code alg}대로 검증 방식을 고르면, 공개키를 HMAC 비밀키로 써서 만든
     * HS256 토큰(알고리즘 혼동 공격)이 통과할 수 있다. 공개키는 이름 그대로 공개된 값이라 누구나 그런 토큰을 만들 수 있다.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(verificationKeys(properties)))
                .jwsAlgorithm(SIGNING_ALGORITHM)
                // 기본 설정은 typ가 없거나 JWT인 토큰만 받는다. Access Token 타입만 받도록 바꾼다.
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType(TOKEN_TYPE))))
                .build();

        // JwtValidators.createDefaultWithIssuer()는 허용 오차 60초가 고정이라, 검증기를 직접 조합한다.
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                // exp가 없는 토큰은 만료 검사를 건너뛰어 영원히 유효해진다. 발급 실수로 빠져도 통과하지 않게 필수로 요구한다.
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                // 만료 판단은 이 검증기 하나가 맡는다. 만료를 위조·형식 오류와 구분할 수 있도록 별도 오류 코드를 남긴다.
                new ExpiredTokenValidator(CLOCK_SKEW, clock),
                KEY_ID_REQUIRED,
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience()))
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * 설정의 공개키들을 {@code kid}가 붙은 JWK 목록으로 만든다.
     * 디코더와 인코더(member)가 같은 공개키·{@code kid}·알고리즘을 쓰도록 한 곳에서 만든다.
     *
     * @throws IllegalStateException 공개키 파일을 읽을 수 없거나 형식이 맞지 않는 경우 ({@link RsaPemKeys} 참고)
     */
    public static JWKSet verificationKeys(JwtProperties properties) {
        List<JWK> keys = properties.verificationKeys().stream()
                .<JWK>map(key -> new RSAKey.Builder(RsaPemKeys.publicKey(key.publicKey()))
                        .keyID(key.keyId())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWS_ALGORITHM)
                        .build())
                .toList();
        return new JWKSet(keys);
    }

}
