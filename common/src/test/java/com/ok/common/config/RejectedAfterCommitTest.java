package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실행기가 작업을 거절하도록 두면 안 되는 이유를 코드로 남긴다. 비동기 실행기에 거절 옵션을 두지 않고,
 * 가득 차면 작업을 넘긴 스레드가 기다리게 한 결정의 근거다(docs/decisions/common/417-async-executors.md).
 *
 * <p>이벤트 리스너는 트랜잭션 커밋 뒤 콜백에서 작업을 넘긴다. 이때 거절되면 어떻게 되는지를 스프링 동작 그대로 확인한다.
 * DB 없이 아무 일도 하지 않는 트랜잭션 관리자를 써서 커밋 뒤 콜백 동작만 본다.
 */
class RejectedAfterCommitTest {

    private static final long WAIT_SECONDS = 5;

    // 커밋 뒤 콜백(afterCommit)만 실행되도록 아무 일도 하지 않는 트랜잭션 관리자
    static class NoopTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    // 이미 커밋된 뒤인데 호출한 쪽에 예외가 올라가 요청이 오류가 되고, 같은 이벤트를 받는 다른 리스너의 콜백도 실행되지 않는다
    @Test
    void 커밋_뒤_콜백에서_작업이_거절되면_커밋_호출이_예외를_던지고_같은_커밋의_다른_콜백은_실행되지_않는다() throws Exception {
        // given: 상한 1개가 이미 찬 거절 실행기
        SimpleAsyncTaskExecutor rejecting = new SimpleAsyncTaskExecutor("rejecting-");
        rejecting.setVirtualThreads(true);
        rejecting.setConcurrencyLimit(1);
        rejecting.setRejectTasksWhenLimitReached(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rejecting.execute(() -> {
            started.countDown();
            awaitQuietly(release);
        });
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        List<String> secondCallbackCalls = new ArrayList<>();
        TransactionTemplate sut = new TransactionTemplate(new NoopTransactionManager());

        // when & then
        try {
            assertThatThrownBy(() -> sut.executeWithoutResult(status -> {
                // 첫 번째 콜백: 리스너 작업을 실행기에 넘긴다 (거절됨)
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        rejecting.execute(() -> { });
                    }
                });
                // 두 번째 콜백: 같은 이벤트를 받는 다른 리스너
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        secondCallbackCalls.add("실행됨");
                    }
                });
            })).isInstanceOf(TaskRejectedException.class);
            assertThat(secondCallbackCalls).isEmpty();
        } finally {
            release.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
