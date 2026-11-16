package com.ok.member.auth.application.service;

import com.ok.common.config.JwtProperties;
import com.ok.common.exception.RestApiException;
import com.ok.member.auth.application.dto.TokenReissueCommand;
import com.ok.member.auth.application.port.out.RefreshTokenRepository;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.domain.RotatedRefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
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
 * Refresh Token을 발급·회전하고 저장한다.
 *
 * <p><b>토큰 형식</b>: JWT가 아닌 무작위 문자열(opaque token)이다.
 * Refresh Token은 어차피 사용할 때마다 DB에서 조회해 유효성을 확인하므로, 토큰 안에 정보를 담을 필요가 없다.
 * 내용이 없으니 탈취되어도 디코딩으로 얻을 정보가 없고, DB에서 지우는 것만으로 즉시 폐기할 수 있다.
 *
 * <p><b>저장 방식</b>: 로그인 세션마다 행 하나를 두고, 재발급하면 같은 행의 해시를 바꾼다({@link RefreshToken#rotate}).
 *
 * <p>로그아웃하면 그 세션의 행을 지운다({@link #revoke}). 같은 회원의 다른 세션(다른 행)은 남는다.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /**
     * 원문 인코딩 방식: URL-safe Base64, 패딩(=) 없음.
     * 쿠키나 URL에 그대로 넣어도 이스케이프가 필요 없는 문자(A-Z a-z 0-9 - _)만 사용한다.
     * 발급 바이트 수와 원문 길이는 요청 검증과 맞추려고 {@link TokenReissueCommand}에 함께 둔다.
     */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

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
     * 새 로그인 세션의 Refresh Token을 발급해 해시를 저장하고, 원문을 반환한다.
     *
     * @param memberId 토큰을 발급받을 회원 id
     * @return 토큰 원문과 만료 일시. 원문은 이 반환값으로만 얻을 수 있다.
     */
    @Transactional
    public IssuedRefreshToken issue(Long memberId) {
        Objects.requireNonNull(memberId, "memberId는 null일 수 없습니다.");

        String tokenValue = generateTokenValue();
        Instant expiresAt = expiresAtFrom(clock.instant());
        refreshTokenRepository.save(
                RefreshToken.create(memberId, TokenHasher.sha256Hex(tokenValue), toClockZone(expiresAt)));

        return new IssuedRefreshToken(tokenValue, expiresAt);
    }

    /**
     * Refresh Token을 사용 처리하고 같은 세션의 새 토큰으로 바꾼다(회전). 한 토큰은 한 번만 쓸 수 있다.
     * 행을 새로 만들거나 지우지 않고, 같은 행의 해시를 새 토큰 해시로 바꾼다.
     *
     * <p>있는 토큰의 행에만 쓰기 잠금을 걸어, 같은 토큰으로 동시에 요청이 와도 하나만 성공한다.
     * 호출한 쪽의 트랜잭션에 참여하므로, 이후 처리(회원 확인)가 실패하면 회전도 함께 취소된다.
     *
     * @param tokenValue 클라이언트가 보낸 토큰 원문
     * @return 새 토큰과 토큰을 발급받은 회원 id
     * @throws RestApiException 없거나 만료되었거나 이미 사용한 토큰이면 {@code INVALID_REFRESH_TOKEN}
     */
    @Transactional
    public RotatedRefreshToken rotate(String tokenValue) {
        Objects.requireNonNull(tokenValue, "tokenValue는 null일 수 없습니다.");
        String tokenHash = TokenHasher.sha256Hex(tokenValue);

        // 잠금 없이 id만 먼저 찾는다. 없는 해시에 잠금 조회를 하면 MySQL이 인덱스 사이 구간(gap)을 잠가,
        // 잘못된 토큰이 반복해 들어올 때 같은 구간에 새 토큰을 저장하는 로그인·재발급이 잠시 기다릴 수 있다.
        // 엔티티가 아닌 id만 받는 이유는 RefreshTokenRepository.findIdByTokenHash에 있다
        Long refreshTokenId = refreshTokenRepository.findIdByTokenHash(tokenHash)
                .orElseThrow(() -> new RestApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 있는 행만 기본 키로 잠근다. 잠금 조회는 커밋된 최신 값을 읽으므로, 그 사이 다른 요청이 먼저 회전했다면
        // 해시가 바뀌어 있다. 그 요청이 지금 토큰을 이미 쓴 것이므로 실패시킨다
        RefreshToken refreshToken = refreshTokenRepository.findForUpdateById(refreshTokenId)
                .filter(locked -> locked.getTokenHash().equals(tokenHash))
                .orElseThrow(() -> new RestApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 저장할 때와 같은 Clock의 시간대로 비교한다 (toClockZone 참고)
        Instant now = clock.instant();
        if (refreshToken.isExpired(LocalDateTime.ofInstant(now, clock.getZone()))) {
            throw new RestApiException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        String newTokenValue = generateTokenValue();
        Instant expiresAt = expiresAtFrom(now);
        refreshToken.rotate(TokenHasher.sha256Hex(newTokenValue),
                toClockZone(now.truncatedTo(ChronoUnit.MICROS)), toClockZone(expiresAt));

        return new RotatedRefreshToken(refreshToken.getMemberId(), new IssuedRefreshToken(newTokenValue, expiresAt));
    }

    /**
     * Refresh Token의 세션(행)을 지운다. 없거나 만료되었거나 이미 지운 토큰이어도 예외 없이 끝난다.
     * 로그아웃의 목적(그 세션이 끝난 상태)은 이미 이뤄져 있고, 응답으로 토큰의 유효 여부를 드러내지 않기 위해서다.
     *
     * <p>만료된 행은 지우지 않고 정리 스케줄러에 맡긴다. 만료된 행은 이미 재발급에 쓸 수 없어 남겨도 세션이 살아나지 않는다.
     * 정리는 만료 일시 인덱스 → 기본 키 순서로, 로그아웃은 기본 키 → 인덱스 순서로 잠가, 같은 만료 행을 함께 지우면 교착 상태가 생길 수 있다.
     *
     * <p>{@link #rotate}와 같은 이유로 해시로는 잠금 없이 id만 찾고, 기본 키로 지운다.
     * 같은 토큰의 재발급이 행을 잠그고 있으면 DELETE가 그 커밋을 기다렸다가, 해시가 바뀌었어도 행을 지운다.
     * id를 찾았다는 것이 그 토큰이 방금까지 이 세션에서 유효했다는 증거이므로, 회전된 새 토큰의 세션도 함께 끝내는 것이 맞다.
     *
     * <p>한계: 재발급이 이미 커밋된 뒤 옛 토큰으로 로그아웃하면 행을 찾지 못해 새 토큰의 세션이 남는다.
     * 막으려면 이전 해시를 저장해야 해서 재사용 탐지(#562)에서 함께 다룬다.
     *
     * @param tokenValue 클라이언트가 보낸 토큰 원문
     */
    @Transactional
    public void revoke(String tokenValue) {
        Objects.requireNonNull(tokenValue, "tokenValue는 null일 수 없습니다.");

        // 저장할 때와 같은 Clock의 시간대로 비교한다 (toClockZone 참고)
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        refreshTokenRepository.findUnexpiredIdByTokenHash(TokenHasher.sha256Hex(tokenValue), now)
                .ifPresent(refreshTokenRepository::deleteDirectlyById);
    }

    /**
     * 기준 시각에 이미 만료된 기록을 최대 {@code batchSize}개 지운다. 호출마다 짧은 트랜잭션 하나로 끝난다.
     *
     * @return 지운 행 수
     */
    @Transactional
    public int deleteExpiredBatch(LocalDateTime now, int batchSize) {
        return refreshTokenRepository.deleteExpiredBatch(now, batchSize);
    }

    // DB 컬럼이 DATETIME(6)(마이크로초)이라, 나노초가 섞이면 반환값과 저장값이 달라질 수 있다.
    // 마이크로초 단위로 맞춰 두 값을 정확히 일치시킨다.
    private Instant expiresAtFrom(Instant now) {
        return now.plus(jwtProperties.refreshTokenExpiration()).truncatedTo(ChronoUnit.MICROS);
    }

    // 엔티티는 LocalDateTime으로 저장하므로 Clock의 시간대로 바꾼다. 시간대 변환은 이 한 곳에서만 한다.
    private LocalDateTime toClockZone(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }

    private String generateTokenValue() {
        byte[] bytes = new byte[TokenReissueCommand.REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

}
