package com.ok.member.auth.service;

import com.ok.member.MemberRole;
import com.ok.member.auth.config.JwtProperties;
import com.ok.member.auth.domain.AccessToken;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 회원 정보로 Access Token을 발급한다.
 *
 *  <p>토큰에 담는 정보
 *  <ul>
 *    <li>iss(발급자): 우리 서비스가 발급한 토큰인지 검증할 때 사용</li>
 *    <li>sub(주체): memberId. 다른 모듈은 이 값만으로 요청한 회원을 식별한다.</li>
 *    <li>role: 회원 권한. 이후 인가 규칙에서 ROLE_BUYER 같은 권한으로 변환해 사용한다.</li>
 *    <li>iat(발급 시각), exp(만료 시각)</li>
 *  </ul>
 *
 *  <p>이름, 이메일 같은 개인정보는 넣지 않는다. JWT는 서명만 되어 있을 뿐 암호화되지 않아서
 *  누구나 Base64 디코딩으로 내용을 볼 수 있고, 회원 정보가 바뀌어도 토큰이 만료될 때까지 예전 값이 남는다.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenProvider {

    public static final String ROLE_CLAIM = "role";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public AccessToken issue(Long memberId, MemberRole role) {
        // JWT의 시각 claim(iat, exp)은 초 단위로 저장된다.
        // 나노초까지 포함된 Instant를 그대로 쓰면, 반환하는 expiresAt과 토큰 안의 exp가 1초 미만으로 달라진다.
        // 처음부터 초 단위로 잘라서 두 값을 정확히 일치시킨다.
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(jwtProperties.accessTokenExpiration());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(String.valueOf(memberId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(ROLE_CLAIM, role.name())
                .build();

        // 대칭키 인코더는 알고리즘을 스스로 고르지 못하므로 HS256을 명시한다. (JwtConfig 참고)
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(tokenValue, expiresAt);
    }

}
