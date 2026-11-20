package com.ok.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ScheduledTasksObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;

/**
 * 핸들러를 직접 부르지 않고, Boot의 ObservationRegistry와 {@code @Scheduled} 실행 경로로 검증한다.
 * 스케줄러가 실제로 실행한 작업과, 같은 작업({@code ScheduledMethodRunnable})을 테스트 스레드에서 돌린 경우를 모두 본다.
 */
class ScheduledTraceObservationHandlerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class, ScheduledTasksObservationAutoConfiguration.class))
            .withUserConfiguration(SchedulingFixture.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(TraceIdGenerator.class, () -> new TraceIdGenerator(Clock.systemUTC(), 0))
            .withBean(ScheduledTraceObservationHandler.class,
                    () -> new ScheduledTraceObservationHandler(new TraceIdGenerator(Clock.systemUTC(), 0)));

    // @Configuration을 붙이지 않는다. common의 테스트 클래스는 다른 모듈 통합 테스트의 컴포넌트 스캔에도 노출된다
    @EnableScheduling
    static class SchedulingFixture {

        static final List<String> seenByScheduler = new CopyOnWriteArrayList<>();
        static final List<String> seenByManualRun = new CopyOnWriteArrayList<>();
        static volatile CountDownLatch firedBySpring;
        static volatile boolean manual;

        @org.springframework.context.annotation.Bean
        Job job() {
            return new Job();
        }
    }

    static class Job {

        // 첫 실행은 스케줄러가 곧바로 하고, 이후 실행은 테스트가 직접 돌릴 때만 일어나도록 간격을 아주 길게 둔다
        @Scheduled(initialDelay = 0, fixedDelay = 1_000_000)
        void run() {
            String traceId = MDC.get("traceId");
            if (SchedulingFixture.manual) {
                SchedulingFixture.seenByManualRun.add(traceId);
                return;
            }
            SchedulingFixture.seenByScheduler.add(traceId);
            SchedulingFixture.firedBySpring.countDown();
        }
    }

    @BeforeEach
    void prepare() {
        SchedulingFixture.firedBySpring = new CountDownLatch(1);
    }

    @AfterEach
    void reset() {
        SchedulingFixture.seenByScheduler.clear();
        SchedulingFixture.seenByManualRun.clear();
        SchedulingFixture.manual = false;
        MDC.clear();
    }

    @Test
    void 스케줄러가_실행한_작업의_MDC에_새_traceId가_있다() {
        // when & then
        runner.run(context -> {
            assertThat(SchedulingFixture.firedBySpring.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(SchedulingFixture.seenByScheduler).hasSize(1);
            assertThat(SchedulingFixture.seenByScheduler.get(0)).matches("[0-9]{1,19}");
        });
    }

    @Test
    void 실행마다_다른_traceId이고_끝나면_실행한_스레드의_MDC를_진입_전으로_되돌린다() {
        // when & then
        runner.run(context -> {
            assertThat(SchedulingFixture.firedBySpring.await(10, TimeUnit.SECONDS)).isTrue();
            SchedulingFixture.manual = true;
            Runnable sut = scheduledRunnable(context.getBean(ScheduledAnnotationBeanPostProcessor.class), "$Job.run");
            MDC.put("before", "value");

            sut.run();
            String afterFirstRun = MDC.get("traceId");
            sut.run();

            assertSoftly(softly -> {
                softly.assertThat(SchedulingFixture.seenByManualRun).hasSize(2);
                softly.assertThat(SchedulingFixture.seenByManualRun.get(0)).matches("[0-9]{1,19}");
                softly.assertThat(SchedulingFixture.seenByManualRun.get(0)).isNotEqualTo(SchedulingFixture.seenByManualRun.get(1));
                softly.assertThat(SchedulingFixture.seenByManualRun.get(0)).isNotEqualTo(SchedulingFixture.seenByScheduler.get(0));
                softly.assertThat(afterFirstRun).isNull();
                softly.assertThat(MDC.get("before")).isEqualTo("value");
                softly.assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("before");
            });
        });
    }

    @Test
    void 작업이_예외로_끝나도_MDC를_되돌린다() {
        // given
        ApplicationContextRunner failing = runner.withUserConfiguration(FailingFixture.class);

        // when & then
        failing.run(context -> {
            Runnable sut = scheduledRunnable(context.getBean(ScheduledAnnotationBeanPostProcessor.class), "$FailingJob.fail");
            MDC.put("before", "value");

            try {
                sut.run();
            } catch (IllegalStateException expected) {
                // 작업이 던진 예외는 호출자에게 그대로 전달된다
            }

            assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("before");
        });
    }

    static class FailingFixture {

        @org.springframework.context.annotation.Bean
        FailingJob failingJob() {
            return new FailingJob();
        }
    }

    static class FailingJob {

        @Scheduled(initialDelay = 1_000_000, fixedDelay = 1_000_000)
        void fail() {
            throw new IllegalStateException("실패");
        }
    }

    // 스케줄러가 등록한 작업의 Runnable. Spring이 결과 기록용으로 감싼 것이라 toString("클래스.메서드")으로 찾는다
    private static Runnable scheduledRunnable(ScheduledAnnotationBeanPostProcessor processor, String methodSuffix) {
        List<ScheduledTask> tasks = new ArrayList<>(processor.getScheduledTasks());
        return tasks.stream()
                .map(task -> task.getTask().getRunnable())
                .filter(runnable -> runnable.toString().endsWith(methodSuffix))
                .findFirst()
                .orElseThrow();
    }
}
