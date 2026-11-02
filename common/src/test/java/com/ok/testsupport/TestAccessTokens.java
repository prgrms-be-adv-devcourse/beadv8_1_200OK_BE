package com.ok.testsupport;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ok.common.security.TokenClaims;
import com.ok.common.config.JwtConfig;
import com.ok.common.config.JwtProperties;
import com.ok.common.config.RsaPemKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 통합 테스트에서 요청에 붙일 Access Token을 만든다.
 *
 * <p>실제 서비스({@code AccessTokenProvider})와 같은 인코더·알고리즘·헤더(typ, kid)·클레임(iss, aud, roles)으로 만들어,
 * 보안 필터가 실제 토큰과 똑같이 검증한다. 형식이 바뀌면 이 클래스도 함께 바꿔야 실제 토큰과 어긋나지 않는다.
 * 로그인 API를 거치지 않아 어느 모듈의 테스트에서든 회원과 권한을 정해 토큰을 만들 수 있다.
 *
 * <pre>
 * mockMvc.perform(get("/api/v1/cart")
 *         .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "BUYER")));
 * mockMvc.perform(post("/api/v1/products")
 *         .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "BUYER", "SELLER")));
 * </pre>
 *
 * <p>{@code AbstractIntegrationTest}를 상속한 테스트에서 주입받아 쓴다.
 * MockMvc는 {@code .apply(springSecurity())}로 만들어야 보안 필터가 적용된다.
 */
@Component
public class TestAccessTokens {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Duration VALIDITY = Duration.ofMinutes(30);

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final String keyId;

    // member의 JwtEncoder 빈을 주입받지 않고 같은 키·알고리즘으로 직접 만든다. 그래야 member가 없는 모듈의 테스트에서도 쓸 수 있다.
    // 개인키는 member의 JwtSigningProperties와 같은 설정(jwt.signing.*)에서 읽는다. common은 member의 타입을 쓸 수 없어 값으로 받는다
    public TestAccessTokens(JwtProperties jwtProperties,
                            @Value("${jwt.signing.key-id}") String keyId,
                            @Value("${jwt.signing.private-key}") Resource privateKey) {
        this.jwtProperties = jwtProperties;
        this.keyId = keyId;
        JWK verificationKey = JwtConfig.verificationKeys(jwtProperties).getKeyByKeyId(keyId);
        // 그냥 두면 NPE만 나서 원인(테스트 설정의 kid 불일치)을 찾기 어렵다
        if (verificationKey == null) {
            throw new IllegalStateException(
                    "jwt.signing.key-id(%s)의 공개키가 jwt.verification-keys에 없습니다. application-test.yml을 확인하세요."
                            .formatted(keyId));
        }
        RSAKey publicKey = verificationKey.toRSAKey();
        RSAKey signingKey = new RSAKey.Builder(publicKey).privateKey(RsaPemKeys.privateKey(privateKey)).build();
        this.jwtEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    /**
     * 유효한 토큰을 {@code Authorization} 헤더 값 형태({@code Bearer ...})로 만든다.
     *
     * @param roles BUYER, SELLER, ADMIN 중 하나 이상. 비우면 권한이 없는 토큰이 된다.
     */
    public String bearer(Long memberId, String... roles) {
        return bearerForSubject(String.valueOf(memberId), roles);
    }

    /**
     * subject를 직접 지정한 토큰을 만든다. 회원 id가 아닌 subject(예: 숫자가 아닌 값) 처리를 검증할 때 쓴다.
     */
    public String bearerForSubject(String subject, String... roles) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return BEARER_PREFIX + encode(subject, List.of(roles), now, now.plus(VALIDITY));
    }

    /**
     * 1분 전에 만료된 토큰을 {@code Authorization} 헤더 값 형태로 만든다. 만료 토큰 거부를 검증할 때 쓴다.
     */
    public String expiredBearer(Long memberId, String... roles) {
        Instant expiresAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(1));
        return BEARER_PREFIX + encode(String.valueOf(memberId), List.of(roles), expiresAt.minus(VALIDITY), expiresAt);
    }

    private String encode(String subject, List<String> roles, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(subject)
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(TokenClaims.ROLES, roles)
                .build();
        // 서비스와 같은 알고리즘·타입·키 식별자를 쓴다 (값은 JwtConfig, application-test.yml 한 곳에서 관리)
        JwsHeader header = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM)
                .type(JwtConfig.TOKEN_TYPE)
                .keyId(keyId)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

}
