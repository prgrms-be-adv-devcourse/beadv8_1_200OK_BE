package com.ok.testsupport;

import com.ok.common.security.TokenClaims;
import com.ok.common.config.JwtConfig;
import com.ok.common.config.JwtProperties;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
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

    public TestAccessTokens(JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
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
        // 서비스와 같은 알고리즘·타입·키 식별자를 쓴다 (값은 JwtConfig, JwtProperties 한 곳에서 관리)
        JwsHeader header = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM)
                .type(JwtConfig.TOKEN_TYPE)
                .keyId(jwtProperties.keyId())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

}
