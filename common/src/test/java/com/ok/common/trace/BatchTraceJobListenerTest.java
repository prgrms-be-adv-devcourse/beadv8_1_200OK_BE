package com.ok.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 실제 {@code AbstractJob.execute} 경로로 검증한다. Job은 최소 구현(작업 중 MDC를 기록만 하는 Job)이다.
 */
class BatchTraceJobListenerTest {

    // 실행하는 동안의 MDC traceId를 기록하는 최소 Job
    static class ProbeJob extends AbstractJob {

        final List<String> seenInside = new CopyOnWriteArrayList<>();

        ProbeJob() {
            super("probeJob");
            setJobRepository(new ResourcelessJobRepository());
        }

        @Override
        public Step getStep(String stepName) {
            return null;
        }

        @Override
        public Collection<String> getStepNames() {
            return List.of();
        }

        @Override
        protected void doExecute(JobExecution execution) throws JobExecutionException {
            seenInside.add(MDC.get("traceId"));
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TraceConfig.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ProbeJob.class, ProbeJob::new);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static JobExecution newExecution(long id) {
        return new JobExecution(id, new JobInstance(id, "probeJob"), new JobParameters());
    }

    @Test
    void Job을_실행하는_동안_MDC에_새_traceId가_있다() {
        // when & then
        runner.run(context -> {
            ProbeJob sut = context.getBean(ProbeJob.class);

            sut.execute(newExecution(1));

            assertThat(sut.seenInside).hasSize(1);
            assertThat(sut.seenInside.get(0)).matches("[0-9]{1,19}");
        });
    }

    @Test
    void 실행마다_다른_traceId이고_끝나면_진입_전_MDC로_되돌린다() {
        // when & then
        runner.run(context -> {
            ProbeJob sut = context.getBean(ProbeJob.class);
            MDC.put("traceId", "http-request");
            MDC.put("orderId", "7");

            sut.execute(newExecution(1));
            sut.execute(newExecution(2));

            assertSoftly(softly -> {
                softly.assertThat(sut.seenInside).hasSize(2);
                softly.assertThat(sut.seenInside.get(0)).isNotEqualTo(sut.seenInside.get(1)).isNotEqualTo("http-request");
                softly.assertThat(MDC.get("traceId")).isEqualTo("http-request");
                softly.assertThat(MDC.get("orderId")).isEqualTo("7");
            });
        });
    }

    @Test
    void Job이_실패해도_MDC를_되돌린다() {
        // given
        ApplicationContextRunner failing = new ApplicationContextRunner()
                .withUserConfiguration(TraceConfig.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(ProbeJob.class, () -> new ProbeJob() {
                    @Override
                    protected void doExecute(JobExecution execution) {
                        throw new IllegalStateException("실패");
                    }
                });

        // when & then
        failing.run(context -> {
            ProbeJob sut = context.getBean(ProbeJob.class);
            MDC.put("traceId", "before");

            JobExecution execution = newExecution(1);
            sut.execute(execution);

            assertSoftly(softly -> {
                softly.assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
                softly.assertThat(MDC.get("traceId")).isEqualTo("before");
            });
        });
    }

    // 이미 중지된 Job은 beforeJob 없이 afterJob만 불린다
    @Test
    void beforeJob_없이_afterJob만_불려도_MDC를_건드리지_않는다() {
        // when & then
        runner.run(context -> {
            ProbeJob sut = context.getBean(ProbeJob.class);
            MDC.put("traceId", "before");
            JobExecution stopping = newExecution(1);
            stopping.setStatus(BatchStatus.STOPPING);

            sut.execute(stopping);

            assertSoftly(softly -> {
                softly.assertThat(sut.seenInside).isEmpty();
                softly.assertThat(MDC.get("traceId")).isEqualTo("before");
            });
        });
    }

    @Test
    void 다른_Job이_연_범위는_닫지_않는다() {
        // given
        BatchTraceJobListener sut = new BatchTraceJobListener(new TraceIdGenerator(Clock.systemUTC(), 0));
        MDC.put("traceId", "before");
        sut.beforeJob(newExecution(1));
        String inside = MDC.get("traceId");

        // when
        sut.afterJob(newExecution(2));

        // then
        assertThat(MDC.get("traceId")).isEqualTo(inside).isNotEqualTo("before");
        sut.afterJob(newExecution(1));
        assertThat(MDC.get("traceId")).isEqualTo("before");
    }

    @Test
    void Batch가_클래스패스에_없는_앱도_기동하고_Job_리스너_등록기는_없다() {
        // given
        ApplicationContextRunner withoutBatch = new ApplicationContextRunner()
                .withUserConfiguration(TraceConfig.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withClassLoader(new FilteredClassLoader("org.springframework.batch"));

        // when & then
        withoutBatch.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TraceIdGenerator.class);
            assertThat(context).doesNotHaveBean("batchTraceJobListenerRegistrar");
        });
    }

    @Test
    void Batch가_있으면_Job_리스너_등록기가_등록된다() {
        // when & then
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("batchTraceJobListenerRegistrar");
            assertThat(context.getBean("batchTraceJobListenerRegistrar")).isInstanceOf(BeanPostProcessor.class);
        });
    }
}
