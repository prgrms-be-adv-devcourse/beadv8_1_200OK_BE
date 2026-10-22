package com.ok.common.trace;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// @Scheduled가 쓰는 스케줄러에서 실행되는 작업이 traceId를 받는지 확인한다
class TraceSchedulingIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TraceProbeListener probe;

    @Test
    void 스케줄러가_실행하는_작업은_traceId를_가진다() {
        // given
        List<String> observed = new CopyOnWriteArrayList<>();

        // when
        taskScheduler.schedule(() -> observed.add(String.valueOf(MDC.get(TraceContext.MDC_KEY))), Instant.now());

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(observed).hasSize(1).allMatch(id -> id.matches("[0-9]+")));
    }

    @Test
    void 크론으로_반복_실행되는_작업은_실행마다_새_traceId를_가진다() {
        // given
        List<String> observed = new CopyOnWriteArrayList<>();

        // when: 매초 실행되는 크론. @Scheduled(cron)과 같은 경로(ReschedulingRunnable)를 탄다
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> observed.add(String.valueOf(MDC.get(TraceContext.MDC_KEY))),
                new CronTrigger("* * * * * *"));
        try {
            await().atMost(TIMEOUT).until(() -> observed.size() >= 3);
        } finally {
            future.cancel(true);
        }

        // then
        assertThat(observed).allMatch(id -> id.matches("[0-9]+"));
        assertThat(Set.copyOf(observed)).hasSameSizeAs(observed);
    }

    @Test
    void 스케줄러_작업이_발행한_이벤트의_리스너도_같은_traceId를_본다() {
        // given
        String key = "scheduled-" + UUID.randomUUID();
        AtomicReference<String> schedulerTraceId = new AtomicReference<>();

        // when
        taskScheduler.schedule(() -> {
            schedulerTraceId.set(MDC.get(TraceContext.MDC_KEY));
            transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(new TraceProbeEvent(key)));
        }, Instant.now());

        // then
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(schedulerTraceId.get()).matches("[0-9]+");
            assertThat(probe.observed(key)).containsExactly(schedulerTraceId.get());
        });
    }
}
