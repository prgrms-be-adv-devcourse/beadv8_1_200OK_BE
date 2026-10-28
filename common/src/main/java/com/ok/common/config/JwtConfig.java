package com.ok.common.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * JWT 검증에 사용하는 빈과 발급·검증이 함께 쓰는 값(알고리즘, 토큰 타입, 비밀키)을 둔다.
 *
 * <p>검증({@link JwtDecoder})은 모든 모듈 앱이 보안 필터를 쓰기 위해 필요하므로 common에 둔다.
 * 발급({@code JwtEncoder})은 토큰을 만드는 member 모듈의 {@code JwtEncoderConfig}가 맡는다.
 *
 * <p>JWT를 직접 파싱하거나 서명하는 코드를 만들지 않고, Spring Security가 제공하는
 * {@code JwtEncoder} / {@link JwtDecoder}(내부 구현: Nimbus JOSE + JWT)를 사용한다.
 *
 * <p>대칭키 방식(HS256)을 사용한다. 발급과 검증을 같은 애플리케이션에서 하므로 비대칭키의 장점
 * (검증하는 쪽은 위조할 수 없음)이 쓰이지 않고, 키 관리가 단순하다. 토큰을 검증하는 곳이 둘 이상이 되면
 * 개인키를 공유하지 않아도 되는 비대칭키(RS256 등)로 바꾼다. 서명 알고리즘과 키는 이 클래스와 {@code JwtEncoderConfig}에서만 관리하므로
 * 전환할 때 각 모듈의 인가 코드는 바뀌지 않는다.
 *
 * <p>토큰 헤더의 {@code kid}로 서명 키를 구분한다. 지금은 키가 하나지만, 키를 교체할 때 옛 키와 새 키를
 * 함께 받아들이는 기간을 둘 수 있도록 미리 넣는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /**
     * 서명 알고리즘(JWS 기준 이름: HS256). 발급(AccessTokenProvider)과 검증(jwtDecoder)이 같은 값을 쓰도록 한 곳에서 정한다.
     */
    public static final MacAlgorithm SIGNING_ALGORITHM = MacAlgorithm.HS256;

    /**
     * Access Token의 타입(typ 헤더). RFC 9068이 정한 Access Token용 값이다.
     * 용도가 다른 JWT가 생겨도 Access Token으로 쓰이지 않도록, 검증 시 이 타입만 받아들인다. (RFC 8725 권고)
     */
    public static final String TOKEN_TYPE = "at+jwt";

    /**
     * 같은 알고리즘의 Java(JCA) 표준 이름. SecretKeySpec은 JWS 이름("HS256")이 아니라 이 이름을 사용한다.
     * MacAlgorithm.HS256.getName()은 "HS256"을 반환하므로 SecretKeySpec에 그대로 쓸 수 없다.
     */
    private static final String JCA_ALGORITHM = "HmacSHA256";

    /**
     * 만료 시간 허용 오차. 기본값은 60초지만, 발급과 검증을 같은 애플리케이션(NTP로 시간 동기화된 서버)에서 하므로
     * 서버 간 시계 차이를 보정할 필요가 없다. 설정한 유효 기간(30분)이 정확히 지켜지도록 0으로 둔다.
     * 만료는 {@link ExpiredTokenValidator} 하나가 이 값으로 판단한다.
     */
    private static final Duration CLOCK_SKEW = Duration.ZERO;

    /**
     * 토큰을 검증하는 디코더. 보안 필터({@code SecurityConfig}의 oauth2ResourceServer)가 요청마다 이 빈으로 토큰을 검증한다.
     *
     * <p>검증 항목
     * <ul>
     *   <li>서명: 같은 비밀키, HS256 알고리즘으로 서명되었는지 (위조·변조된 토큰 거부)</li>
     *   <li>타입(typ): Access Token({@link #TOKEN_TYPE})인지</li>
     *   <li>만료(exp): 필수이며, 허용 오차 없이 검증 ({@link #CLOCK_SKEW})</li>
     *   <li>발급자(iss): 우리 서비스가 발급한 토큰인지</li>
     *   <li>대상(aud): 이 서비스를 위한 토큰인지</li>
     * </ul>
     *
     * <p>활성 시작(nbf)은 우리가 발급하는 토큰에 넣지 않으므로 검증하지 않는다.
     * nbf를 발급하게 되면 {@code JwtTimestampValidator}를 검증기 목록에 다시 추가한다.
     *
     * <p>지금은 키가 하나라 {@code kid}로 키를 고르지 않는다. 키가 하나인 동안에는 {@code kid}가 달라도
     * 우리 비밀키로 서명된 토큰이라 통과하며, {@code kid}는 키를 교체할 때 의미를 갖는다.
     */
    // TODO: 키 교체 시 여러 키 중 kid로 검증할 키를 고르고, 등록되지 않은 kid는 거부한다.
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(SIGNING_ALGORITHM)
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
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience()))
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * 설정의 비밀키(Base64 문자열)를 디코딩해 HMAC-SHA256용 SecretKey로 변환한다.
     * 인코더(member)와 디코더가 반드시 같은 키를 쓰도록 한 곳에서 만든다.
     */
    public static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secretKeyBytes(), JCA_ALGORITHM);
    }

}
