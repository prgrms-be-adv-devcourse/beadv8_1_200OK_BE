package com.ok.member.auth.domain;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 클라이언트에게 전달할, 새로 발급된 Refresh Token.
 *
 * <p>원문({@code value})은 이 객체로 클라이언트에게 한 번 전달되고, 서버에는 해시만 남는다.
 * 원문을 다시 조회할 방법이 없으므로 발급 응답에서 반드시 전달해야 한다.
 *
 * @param value     토큰 원문
 * @param expiresAt 만료 시각. 시간대 해석이 필요 없도록 Instant로 반환한다. (DB에는 Clock 시간대의 일시로 저장)
 */
public record IssuedRefreshToken(String value, Instant expiresAt) {

    /**
     * 토큰 원문은 그 자체로 인증 수단이라 로그에 남으면 안 된다.
     */
    @Override
    public String toString() {
        return "IssuedRefreshToken[value=****, expiresAt=%s]".formatted(expiresAt);
    }

}
