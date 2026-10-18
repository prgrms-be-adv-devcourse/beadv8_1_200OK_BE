package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.auth.config.JwtProperties;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;

/**
 * Refresh Token을 발급하고 저장한다.
 *
 * <p><b>토큰 형식</b>: JWT가 아닌 무작위 문자열(opaque token)이다.
 * Refresh Token은 어차피 사용할 때마다 DB에서 조회해 유효성을 확인하므로, 토큰 안에 정보를 담을 필요가 없다.
 * 내용이 없으니 탈취되어도 디코딩으로 얻을 정보가 없고, DB에서 지우는 것만으로 즉시 폐기할 수 있다.
 *
 * <p>로그아웃(삭제)은 별도 이슈에서 이 서비스에 추가한다.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /** 토큰 원문의 무작위 바이트 수. 32바이트 = 256비트로, 추측이 현실적으로 불가능한 크기다. */
    private static final int TOKEN_BYTES = 32;

    /**
     * 원문 인코딩 방식: URL-safe Base64, 패딩(=) 없음.
     * 쿠키나 URL에 그대로 넣어도 이스케이프가 필요 없는 문자(A-Z a-z 0-9 - _)만 사용한다. 32바이트 → 43자.
     */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * 토큰 원문의 길이. 32바이트를 패딩 없는 Base64URL로 인코딩하면 43자다 (ceil(32 * 4 / 3)).
     * 요청 검증({@code TokenReissueRequest})이 이 값을 기준으로 길이를 제한한다. 바이트 수를 바꾸면 이 값도 함께 바꾼다.
     */
    public static final int TOKEN_LENGTH = 43;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    /** 현재 시각 기준. 테스트에서 고정할 수 있도록 주입받는다. (ClockConfig 참고) */
    private final Clock clock;

    /**
     * 암호학적으로 안전한 난수 생성기. java.util.Random은 다음 값을 예측할 수 있어 토큰 생성에 쓰면 안 된다.
     * SecureRandom은 스레드에 안전해서 하나를 만들어 재사용한다.
     * (초기값이 있는 final 필드는 @RequiredArgsConstructor 생성자 파라미터에 포함되지 않는다.)
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Refresh Token을 발급해 해시를 저장하고, 원문을 반환한다.
     *
     * @param memberId 토큰을 발급받을 회원 id
     * @return 토큰 원문과 만료 일시. 원문은 이 반환값으로만 얻을 수 있다.
     */
    @Transactional
    public IssuedRefreshToken issue(Long memberId) {
        Objects.requireNonNull(memberId, "memberId는 null일 수 없습니다.");

        String tokenValue = generateTokenValue();

        // DB 컬럼이 DATETIME(6)(마이크로초)이라, 나노초가 섞이면 반환값과 저장값이 달라질 수 있다.
        // 마이크로초 단위로 맞춰 두 값을 정확히 일치시킨다.
        Instant expiresAt = clock.instant()
                .plus(jwtProperties.refreshTokenExpiration())
                .truncatedTo(ChronoUnit.MICROS);

        // 엔티티는 LocalDateTime으로 저장하므로 Clock의 시간대로 바꾼다. 시간대 변환은 이 한 곳에서만 한다.
        LocalDateTime expiresAtInClockZone = LocalDateTime.ofInstant(expiresAt, clock.getZone());
        refreshTokenRepository.save(
                RefreshToken.create(memberId, TokenHasher.sha256Hex(tokenValue), expiresAtInClockZone));

        return new IssuedRefreshToken(tokenValue, expiresAt);
    }

    /**
     * Refresh Token을 사용 처리(삭제)하고, 토큰을 발급받은 회원 id를 반환한다. 한 토큰은 한 번만 쓸 수 있다.
     *
     * <p>있는 토큰의 행에만 쓰기 잠금을 걸어, 같은 토큰으로 동시에 요청이 와도 하나만 성공한다.
     * 호출한 쪽의 트랜잭션에 참여하므로, 이후 처리(회원 확인, 새 토큰 발급)가 실패하면 삭제도 함께 취소된다.
     *
     * @param tokenValue 클라이언트가 보낸 토큰 원문
     * @return 토큰을 발급받은 회원 id
     * @throws RestApiException 없거나 만료되었거나 이미 사용한 토큰이면 {@code INVALID_REFRESH_TOKEN}
     */
    @Transactional
    public Long consume(String tokenValue) {
        Objects.requireNonNull(tokenValue, "tokenValue는 null일 수 없습니다.");

        // 잠금 없이 먼저 찾는다. 없는 해시에 잠금 조회를 하면 MySQL이 인덱스 사이 구간(gap)을 잠가,
        // 잘못된 토큰이 반복해 들어올 때 같은 구간에 새 토큰을 저장하는 로그인·재발급이 잠시 기다릴 수 있다.
        Long refreshTokenId = refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(tokenValue))
                .map(RefreshToken::getId)
                .orElseThrow(() -> new RestApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 있는 행만 기본 키로 잠근다. 그 사이 다른 요청이 먼저 써서 지웠다면 여기서 찾지 못한다
        RefreshToken refreshToken = refreshTokenRepository.findForUpdateById(refreshTokenId)
                .orElseThrow(() -> new RestApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 저장할 때와 같은 Clock의 시간대로 비교한다 (issue() 참고)
        if (refreshToken.isExpired(LocalDateTime.now(clock))) {
            throw new RestApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        refreshTokenRepository.delete(refreshToken);
        return refreshToken.getMemberId();
    }

    private String generateTokenValue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

}
