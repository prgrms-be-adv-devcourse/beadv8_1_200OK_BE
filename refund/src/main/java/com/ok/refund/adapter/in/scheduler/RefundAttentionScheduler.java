package com.ok.refund.adapter.in.scheduler;

import com.ok.refund.application.port.in.FindRefundsNeedingAttentionUseCase;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * 한 시간마다 깨어나 사람이 봐야 하는 처리 중 환불을 찾아 알리는 알람이다(#577 멈춘 환불 찾기).
 * 다시 진행 알람({@link RefundResumeScheduler})과 따로 돈다. 다시 진행은 1분마다 돌아, 거기서 알리면 멈춘 환불 1건으로 하루 1,440줄이 남는다.
 * 서버가 여러 대여도 한 번에 한 서버만 돌게 ShedLock으로 잠근다(다시 진행 알람과 같은 잠금 표, 다른 잠금 이름). 잠그지 않으면 서버마다 같은 알림이 남는다.
 * 테스트 프로필에서는 켜지 않는다. 테스트는 유스케이스를 직접 부른다.
 */
@Component
@Profile("!test")
public class RefundAttentionScheduler {

    static final String LOCK_NAME = "refundAttention";
    // 조회 한 번이라 보통 1초 안에 끝난다. 잠근 서버가 죽어도 다음 회차(1시간 뒤) 전에 풀리면 충분하다
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(10);
    // 서버마다 시계가 조금 달라 같은 회차를 두 서버가 이어 도는 것을 막는다
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofMinutes(1);

    private final FindRefundsNeedingAttentionUseCase findRefundsNeedingAttentionUseCase;
    private final LockingTaskExecutor lockingTaskExecutor;

    public RefundAttentionScheduler(FindRefundsNeedingAttentionUseCase findRefundsNeedingAttentionUseCase, DataSource dataSource) {
        this.findRefundsNeedingAttentionUseCase = findRefundsNeedingAttentionUseCase;
        this.lockingTaskExecutor = RefundResumeScheduler.lockingTaskExecutor(dataSource);
    }

    // 회차가 끝난 뒤 1시간 쉬고 다시 돈다. 앱이 뜬 직후 다른 작업과 몰리지 않게 5분 뒤 시작한다.
    // 알림은 WARN 로그라, 너무 자주 남기면 같은 환불이 로그를 덮고 너무 드물면 늦게 안다(#577 결정 1시간)
    // TODO(#577): 간격을 설정 파일로 뺄지 정한다
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void run() {
        LockConfiguration lock = new LockConfiguration(ClockProvider.now(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
        Runnable task = findRefundsNeedingAttentionUseCase::findAndReport;
        lockingTaskExecutor.executeWithLock(task, lock);
    }
}
