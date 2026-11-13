package com.ok.payout.config;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ok.payout.PayoutSchema;

import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;

/**
 * 정산 Job 동시 실행을 막는 ShedLock 설정. 잠금은 DB 표(shedlock)에 저장해 서버가 여러 대여도 함께 막는다.
 * 어노테이션 방식(@SchedulerLock)은 쓰지 않고, 호출하는 쪽이 {@link LockingTaskExecutor}로 직접 잠근다.
 */
@Configuration(proxyBeanMethods = false)
public class PayoutJobLockConfig implements DisposableBean {

    /**
     * 연장용 스레드. 빈으로 노출하지 않는다: ScheduledExecutorService 빈이 있으면 Spring의 @Scheduled가
     * 이 스레드 하나를 다른 모듈의 스케줄까지 같이 쓰게 되고, 기본 스케줄러도 만들어지지 않을 수 있다.
     */
    private final ScheduledExecutorService keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "payout-job-lock-keep-alive");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void destroy() {
        keepAliveExecutor.shutdown();
    }

    @Bean
    public LockingTaskExecutor payoutJobLockingTaskExecutor(DataSource dataSource, Clock clock) {
        // 자동 연장은 ShedLock 시계로 만료를 판단한다. 이 시계도 전역 Clock 빈을 따르게 해 "지금"의 기준을 하나로 둔다(#93).
        // ShedLock 시계는 JVM 전체 설정이지만 지금 ShedLock을 쓰는 곳은 정산뿐이다.
        // 주의: Spring을 띄우는 테스트에서 Clock 빈을 고정 시계로 바꾸면 ShedLock 시계도 고정되고, static이라 컨텍스트가 닫혀도
        // 같은 JVM의 다른 테스트에 남아 자동 연장의 만료 판단이 어긋날 수 있다. 지금 Spring 테스트는 모두 실제 시계를 쓴다.
        // 이 호출을 별도 초기화 빈으로 옮겨도 같은 static을 바꾸므로 이 위험은 그대로라 옮기지 않는다.
        ClockProvider.setClock(clock);
        // 표 이름에 스키마를 붙여야 monolith(스키마 여러 개)에서도 정산 표를 찾는다.
        // 서버 시계가 달라도 잠금 시각이 어긋나지 않게 DB 시각을 쓴다.
        JdbcTemplateLockProvider jdbcProvider = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName(PayoutSchema.NAME + ".shedlock")
                        .usingDbTime()
                        .build());
        // 정산이 lockAtMostFor보다 오래 걸려도 잠금이 풀리지 않게 실행 중 자동 연장한다.
        return new DefaultLockingTaskExecutor(new KeepAliveLockProvider(jdbcProvider, keepAliveExecutor));
    }
}
