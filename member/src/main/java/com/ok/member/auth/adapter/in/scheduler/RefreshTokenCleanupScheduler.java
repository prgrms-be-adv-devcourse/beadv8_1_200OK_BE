package com.ok.member.auth.adapter.in.scheduler;

import com.ok.member.auth.application.port.in.RefreshTokenCleanupUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 Refresh Token 기록을 정해진 주기(기본 매시 정각)로 정리한다.
 *
 * <p>주기와 묶음 크기는 {@code auth.refresh-token.cleanup.cron}, {@code auth.refresh-token.cleanup.batch-size}로 바꾼다.
 * 부하 테스트처럼 짧게 돌려야 할 때는 환경 변수({@code AUTH_REFRESH_TOKEN_CLEANUP_CRON})나 {@code -D}로 덮어쓴다.
 * cron을 {@code -}로 두면 스케줄러가 돌지 않는다.
 *
 * <p>서버가 여러 대면 같은 시각에 함께 돈다. 따로 잠금(ShedLock 등)을 두지 않은 이유: 만료된 행을 같은 순서로 지우므로
 * 겹쳐 돌아도 한쪽이 기다렸다 이미 지워진 행을 건너뛸 뿐, 결과가 달라지거나 교착 상태가 나지 않는다.
 */
@Component
public class RefreshTokenCleanupScheduler {

    private final RefreshTokenCleanupUseCase refreshTokenCleanup;
    private final int batchSize;

    // 설정이 없으면 0이 아니라 기본값(1000)이 들어가도록 기본값을 @Value에 함께 둔다
    public RefreshTokenCleanupScheduler(RefreshTokenCleanupUseCase refreshTokenCleanup,
                                        @Value("${auth.refresh-token.cleanup.batch-size:1000}") int batchSize) {
        this.refreshTokenCleanup = refreshTokenCleanup;
        this.batchSize = batchSize;
    }

    // 기본은 매시 정각(한국 시간). 운영 서버가 하루 중 일부 시간에만 떠 있고, cron은 꺼져 있던 동안 놓친 실행을
    // 나중에 돌리지 않는다. 특정 시각 하나로 정하면 그 시각에 서버가 없을 때 정리가 아예 돌지 않으므로, 떠 있는 동안
    // 자주 돌린다. 한 시간 사이에 만료된 행만 짧은 트랜잭션으로 지우므로 업무 시간에 돌아도 재발급을 오래 막지 않는다
    @Scheduled(cron = "${auth.refresh-token.cleanup.cron:0 0 * * * *}", zone = "Asia/Seoul")
    public void run() {
        refreshTokenCleanup.deleteExpired(batchSize);
    }

}
