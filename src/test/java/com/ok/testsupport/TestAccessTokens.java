package com.ok.testsupport;

import com.ok.common.security.TokenClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 통합 테스트에서 요청에 붙일 Access Token을 만든다.
 * 실제 서비스와 같은 {@link JwtEncoder}와 발급자로 서명하므로, 보안 필터가 실제 토큰과 똑같이 검증한다.
 * 로그인 API나 member 모듈 코드를 거치지 않아 어느 모듈의 테스트에서든 쓸 수 있다.
 *
 * <pre>
 * mockMvc.perform(get("/api/v1/cart")
 *         .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "BUYER")));
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
    private final String issuer;

    public TestAccessTokens(JwtEncoder jwtEncoder, @Value("${jwt.issuer}") String issuer) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
    }

    /**
     * 유효한 토큰을 {@code Authorization} 헤더 값 형태({@code Bearer ...})로 만든다.
     *
     * @param role BUYER, SELLER, ADMIN 중 하나
     */
    public String bearer(Long memberId, String role) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return BEARER_PREFIX + encode(memberId, role, now, now.plus(VALIDITY));
    }

    /**
     * 1분 전에 만료된 토큰을 {@code Authorization} 헤더 값 형태로 만든다. 만료 토큰 거부를 검증할 때 쓴다.
     */
    public String expiredBearer(Long memberId, String role) {
        Instant expiresAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(1));
        return BEARER_PREFIX + encode(memberId, role, expiresAt.minus(VALIDITY), expiresAt);
    }

    private String encode(Long memberId, String role, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(memberId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(TokenClaims.ROLE, role)
                .build();
        // 서비스와 같은 HS256으로 서명한다 (대칭키 인코더는 알고리즘을 직접 지정해야 함)
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

}
