package com.ok.refund.adapter.in.scheduler;

import com.ok.refund.RefundSchema;
import com.ok.refund.application.port.in.ResumeDueRefundsUseCase;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * 정해진 시간마다 깨어나 다시 볼 시각이 지난 승인 뒤 단계를 이어 가게 하는 알람이다(#576).
 * 서버가 여러 대여도 한 번에 한 서버만 돌게 ShedLock으로 잠근다(잠금 행은 환불 DB 표 {@code shedlock}).
 * 같은 단계를 두 번 부르지 않게 막는 일은 잠금이 아니라 지휘자의 환불 행 잠금과 상태 확인이 한다. 이 잠금은 서버마다 같은 목록을
 * 훑으며 같은 환불 행을 두고 서로 기다리는 헛일을 없앤다. 그래서 잠금이 풀린 뒤 회차가 겹쳐도 돈이 두 번 나가지는 않는다.
 * 테스트 프로필에서는 켜지 않는다. 통합 테스트가 만든 단계 줄을 알람이 중간에 집으면 결과가 흔들리므로, 테스트는 유스케이스를 직접 부른다.
 */
@Slf4j
@Component
@Profile("!test")
public class RefundResumeScheduler {

    static final String LOCK_NAME = "refundResume";
    // 이 시간이 지나면 잠근 서버가 죽었다고 보고 다른 서버가 잡을 수 있다. 한 회차는 보통 몇 초이고, Kafka가 죽어도 회차 시간 한도
    // (ResumeDueRefundsService.TIME_BUDGET 30초) 덕분에 최대 약 100초라 이 시간 안에 끝난다. 넘더라도 같은 단계는 두 번 불리지 않는다(위 이유).
    // 더 길게 잡으면 잠근 서버가 죽었을 때 알람이 그만큼 오래 멈춘다
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);
    // 회차가 금방 끝나도 이만큼은 잠금을 쥔다. 서버마다 시계가 조금 달라 같은 회차를 바로 이어 도는 것을 막는다
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofSeconds(10);

    private final ResumeDueRefundsUseCase resumeDueRefundsUseCase;
    // 잠금 실행기를 빈으로 두지 않고 여기서 만든다. 정산이 같은 타입(LockingTaskExecutor)의 빈을 타입으로 주입받아,
    // 한 앱(monolith)에 같은 타입 빈이 둘이 되면 주입할 빈을 고르지 못해 앱이 뜨지 않는다
    private final LockingTaskExecutor lockingTaskExecutor;

    public RefundResumeScheduler(ResumeDueRefundsUseCase resumeDueRefundsUseCase, DataSource dataSource) {
        this.resumeDueRefundsUseCase = resumeDueRefundsUseCase;
        this.lockingTaskExecutor = lockingTaskExecutor(dataSource);
    }

    // 환불 알람들(다시 진행, 멈춘 환불 찾기 #577)이 같은 잠금 표를 쓰게 한 곳에서 만든다. 잠금 이름으로 알람끼리 구분한다.
    // 표 이름에 스키마를 붙여야 한 앱(스키마 여러 개)에서도 환불 표를 찾는다. 서버 시계가 달라도 어긋나지 않게 잠금 시각은 DB 시각을 쓴다
    static LockingTaskExecutor lockingTaskExecutor(DataSource dataSource) {
        return new DefaultLockingTaskExecutor(new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName(RefundSchema.NAME + ".shedlock")
                        .usingDbTime()
                        .build()));
    }

    // 회차가 끝난 뒤 1분 쉬고 다시 돈다(fixedDelay). 단계 줄의 다시 시도 간격(1분)과 맞췄다. 앱이 뜬 직후 몰리지 않게 1분 뒤 시작한다.
    // TODO(#577): 간격을 설정 파일로 뺄지 정한다. 지금은 단계 줄의 다시 시도 간격(1분)과 맞춘 상수다
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void run() {
        // 잠금 만료(lock_until)는 usingDbTime이라 DB 시각으로 계산된다. 여기 넘기는 시작 시각은 잠금 설정 객체를 만들 때만 쓰여
        // 어느 시계여도 동작은 같다. 다른 ShedLock 사용처와 맞추려고 ShedLock 시계를 쓴다
        LockConfiguration lock = new LockConfiguration(ClockProvider.now(), LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR);
        Runnable task = this::resumeDue;
        lockingTaskExecutor.executeWithLock(task, lock);
    }

    private void resumeDue() {
        int count = resumeDueRefundsUseCase.resumeDue();
        if (count > 0) {
            log.info("다시 진행 알람이 환불 {}건을 이어 가려고 함", count);
        }
    }
}
