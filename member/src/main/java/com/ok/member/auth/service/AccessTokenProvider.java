package com.ok.member.auth.service;

import com.ok.common.security.TokenClaims;
import com.ok.common.config.JwtConfig;
import com.ok.common.config.JwtProperties;
import com.ok.member.auth.domain.AccessToken;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 회원 정보로 Access Token(JWT)을 발급한다.
 *
 * <p>헤더
 * <ul>
 *   <li>alg: 서명 알고리즘 (HS256)</li>
 *   <li>typ: 토큰 타입 ({@code at+jwt}). 용도가 다른 JWT와 구분한다.</li>
 *   <li>kid: 서명 키 식별자. 키를 교체할 때 어떤 키로 서명했는지 구분한다.</li>
 * </ul>
 *
 * <p>클레임 (RFC 7519 등록 클레임 중 필요한 것만 쓰고, 권한은 비공개 클레임으로 담는다)
 * <ul>
 *   <li>iss(발급자): 우리 서비스가 발급한 토큰인지 검증할 때 사용</li>
 *   <li>sub(주체): memberId. 다른 모듈은 이 값만으로 요청한 회원을 식별한다.</li>
 *   <li>aud(대상): 이 토큰을 받는 서비스. 서비스가 분리되면 다른 서비스용 토큰을 거부하는 데 쓴다.</li>
 *   <li>jti(토큰 id): 토큰마다 고유한 값. 이후 로그아웃·탈취 대응에서 특정 토큰을 개별 폐기(블랙리스트)할 때 사용한다.</li>
 *   <li>iat(발급 시각): 추적용, 특정 시점 이전 토큰을 일괄 무효화할 때 기준</li>
 *   <li>exp(만료 시각)</li>
 *   <li>roles: 권한 목록. 인가 규칙에서 ROLE_BUYER 같은 권한으로 변환해 사용한다.</li>
 * </ul>
 *
 * <p>nbf(사용 시작 시각)는 넣지 않는다. 발급 즉시 유효한 토큰이라 iat와 같은 값이 되고,
 * 서버 간 시계 차이로 정상 토큰이 거부될 위험만 생긴다.
 *
 * <p>이름, 이메일, 닉네임 같은 회원 정보는 넣지 않는다. JWT는 서명만 되어 있을 뿐 암호화되지 않아서
 * 누구나 Base64 디코딩으로 내용을 볼 수 있고, 회원 정보가 바뀌어도 토큰이 만료될 때까지 옛 값이 남는다.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenProvider {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    /** 현재 시각 기준. 테스트에서 고정할 수 있도록 주입받는다. (ClockConfig 참고) */
    private final Clock clock;

    /**
     * Access Token을 발급한다.
     *
     * <p>어떤 권한을 담을지는 호출하는 쪽(로그인·재발급)이 회원 정보로 계산해 넘긴다. 이 클래스는 받은 권한을 담기만 한다.
     *
     * @param memberId 회원 id (sub claim)
     * @param roles    토큰 권한 목록 (roles claim, {@code TokenRoles}의 값)
     * @return 토큰 문자열과 만료 시각
     * @throws NullPointerException     memberId 또는 roles가 null인 경우.
     *                                  null을 그대로 두면 subject가 문자열 "null"인 잘못된 토큰이 발급되므로 즉시 실패시킨다.
     * @throws IllegalArgumentException roles가 비어 있는 경우. 권한이 없는 토큰은 쓸 곳이 없어 발급하지 않는다.
     */
    public AccessToken issue(Long memberId, List<String> roles) {
        Objects.requireNonNull(memberId, "memberId는 null일 수 없습니다.");
        Objects.requireNonNull(roles, "roles는 null일 수 없습니다.");
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles는 비어 있을 수 없습니다.");
        }

        // JWT의 시각 claim(iat, exp)은 초 단위로 저장된다.
        // 나노초까지 포함된 Instant를 그대로 쓰면, 반환하는 expiresAt과 토큰 안의 exp가 1초 미만으로 달라진다.
        // 처음부터 초 단위로 잘라서 두 값을 정확히 일치시킨다.
        Instant issuedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(jwtProperties.accessTokenExpiration());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(String.valueOf(memberId))
                .audience(List.of(jwtProperties.audience()))
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(TokenClaims.ROLES, List.copyOf(roles))
                .build();

        // 대칭키 인코더는 알고리즘을 스스로 고르지 못하므로 명시한다. 검증 쪽과 같은 상수를 사용한다. (JwtConfig 참고)
        // typ은 Access Token임을, kid는 어떤 키로 서명했는지를 나타낸다. 인코더는 kid로 서명 키를 고른다.
        JwsHeader header = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM)
                .type(JwtConfig.TOKEN_TYPE)
                .keyId(jwtProperties.keyId())
                .build();

        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(tokenValue, expiresAt);
    }

}
