package com.ok.member.auth.application.service;

import com.ok.member.auth.application.port.in.RefreshTokenCleanupUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 만료된 Refresh Token 기록을 정해진 크기씩 나눠 지운다.
 *
 * <p>재발급하면 같은 행을 회전하므로 행이 늘지 않지만, 재발급 없이 버려진 세션의 행은 만료 뒤에도 남는다. 이 행을 정리한다.
 *
 * <p>이 클래스에는 트랜잭션을 걸지 않는다. 묶음마다 {@link RefreshTokenService#deleteExpiredBatch}가 짧은 트랜잭션을 따로 열어,
 * 한 번에 많은 행을 지우느라 잠금과 undo 로그를 오래 쥐지 않게 한다. 클래스에 읽기 전용 트랜잭션을 걸면 묶음 삭제가 그 안에 참여해 실패한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupService implements RefreshTokenCleanupUseCase {

    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    @Override
    public int deleteExpired(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize는 1 이상이어야 합니다. batchSize=" + batchSize);
        }
        long startedAt = System.nanoTime();
        // 기준 시각을 처음에 한 번 정해, 정리하는 동안 새로 만료되는 행까지 쫓아가며 끝나지 않는 일이 없게 한다
        LocalDateTime now = LocalDateTime.now(clock);

        int total = 0;
        int deleted;
        do {
            deleted = refreshTokenService.deleteExpiredBatch(now, batchSize);
            total += deleted;
        } while (deleted == batchSize);

        log.info("refresh token cleanup deleted={} elapsedMs={}", total,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        return total;
    }

}
