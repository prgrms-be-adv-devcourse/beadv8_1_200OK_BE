package com.ok.common.trace;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Batch Job이 실행되는 동안 새 traceId를 MDC에 둔다.
 *
 * <p>Job은 호출한 스레드(스케줄러 또는 HTTP 요청 스레드)에서 동기로 실행되므로 {@code beforeJob}과 {@code afterJob}이 같은 스레드에서 불린다.
 * 그래서 이전 MDC를 담은 범위를 스레드별 스택에 보관한다.
 *
 * <p>{@code AbstractJob}은 Job 시작 전 검증이 실패하거나 이미 중지된 Job이면 {@code beforeJob}을 건너뛰고도 {@code afterJob}은 부른다.
 * 그 경우 이 실행이 연 범위가 없으므로 아무것도 닫지 않는다. 실행 id로 짝을 확인해 다른 Job이 연 범위를 닫지 않게 한다.
 */
public class BatchTraceJobListener implements JobExecutionListener {

    private record Opened(long executionId, TraceContext.Scope scope) {
    }

    private final TraceIdGenerator generator;
    private final ThreadLocal<Deque<Opened>> opened = ThreadLocal.withInitial(ArrayDeque::new);

    public BatchTraceJobListener(TraceIdGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        opened.get().push(new Opened(jobExecution.getId(), TraceContext.open(generator.next())));
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Deque<Opened> stack = opened.get();
        Opened top = stack.peek();
        if (top == null || top.executionId() != jobExecution.getId()) {
            return;
        }
        stack.pop().scope().close();
        if (stack.isEmpty()) {
            opened.remove();
        }
    }
}
