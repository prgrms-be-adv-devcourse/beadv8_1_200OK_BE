package com.ok.member.member.application.service;

import com.ok.common.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 토큰 최소 버전을 저장소에 얼마나 남길지 정한다.
 * 버전을 올리기 전에 발급된 Access Token이 모두 만료되면 기록은 필요 없다. 그래서 변경 시각부터 Access Token 수명에 여유 1분을 더한 때까지만 남긴다.
 * 여유는 버전을 올리는 트랜잭션보다 조금 먼저 읽은 로그인이 그 뒤에 토큰을 발급하는 시간을 덮는다.
 * 비밀번호를 바꾼 직후의 기록과 다시 채우기가 같은 기준을 쓰도록 한곳에 둔다.
 */
@Component
@RequiredArgsConstructor
class TokenVersionRecordPolicy {

    private static final Duration MARGIN = Duration.ofMinutes(1);

    private final JwtProperties jwtProperties;
    /** 현재 시각 기준. 테스트에서 고정할 수 있도록 주입받는다. (ClockConfig 참고) */
    private final Clock clock;

    /**
     * 변경 시각 기준으로 기록을 남길 남은 시간을 계산한다.
     *
     * @return 남은 시간. 이미 지났으면 비어 있다(남길 필요가 없다)
     */
    Optional<Duration> remainingTtl(LocalDateTime changedAt) {
        Duration remaining = Duration.between(LocalDateTime.now(clock), changedAt.plus(retention()));
        // Redis는 밀리초 단위로 받으므로 1밀리초 미만은 남길 수 없다
        return remaining.toMillis() < 1 ? Optional.empty() : Optional.of(remaining);
    }

    /**
     * 다시 채울 때 볼 변경 시각의 시작점. 이보다 먼저 바꾼 회원은 기록이 필요 없다.
     */
    LocalDateTime resyncFrom() {
        return LocalDateTime.now(clock).minus(retention());
    }

    private Duration retention() {
        return jwtProperties.accessTokenExpiration().plus(MARGIN);
    }

}
