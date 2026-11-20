package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcTaskDecoratorTest {

    private final MdcTaskDecorator sut = new MdcTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 작업을_넘긴_스레드의_MDC가_실행하는_스레드에서_보인다() throws InterruptedException {
        // given
        MDC.put("traceId", "trace-1");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable decorated = sut.decorate(() -> seen.set(MDC.get("traceId")));

        // when
        runOnOtherThread(decorated);

        // then
        assertThat(seen.get()).isEqualTo("trace-1");
    }

    @Test
    void 넘긴_스레드에_MDC가_없으면_실행하는_스레드에도_없다() throws InterruptedException {
        // given
        AtomicReference<String> seen = new AtomicReference<>("초기값");
        Runnable decorated = sut.decorate(() -> seen.set(MDC.get("traceId")));

        // when
        runOnOtherThread(decorated);

        // then
        assertThat(seen.get()).isNull();
    }

    // 스레드를 재사용하는 실행기에서 앞 작업의 MDC가 다음 작업으로 새지 않아야 한다
    @Test
    void 작업이_끝나면_실행한_스레드의_원래_MDC로_되돌린다() throws InterruptedException {
        // given
        MDC.put("traceId", "넘긴-스레드");
        Runnable decorated = sut.decorate(() -> { });
        AtomicReference<String> before = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        AtomicReference<String> leaked = new AtomicReference<>();

        // when: 실행하는 스레드는 자기만의 MDC(workerValue)를 이미 가지고 있다
        runOnOtherThread(() -> {
            MDC.put("worker", "workerValue");
            before.set(MDC.get("worker"));
            decorated.run();
            after.set(MDC.get("worker"));
            leaked.set(MDC.get("traceId"));
        });

        // then
        assertSoftly(softly -> {
            softly.assertThat(before.get()).isEqualTo("workerValue");
            softly.assertThat(after.get()).isEqualTo("workerValue");
            softly.assertThat(leaked.get()).isNull();
        });
    }

    @Test
    void 작업이_예외로_끝나도_MDC를_되돌린다() throws InterruptedException {
        // given
        MDC.put("traceId", "넘긴-스레드");
        Runnable decorated = sut.decorate(() -> {
            throw new IllegalStateException("작업 실패");
        });
        AtomicReference<String> leaked = new AtomicReference<>("초기값");

        // when
        runOnOtherThread(() -> {
            try {
                decorated.run();
            } catch (IllegalStateException ignored) {
                // 실행기가 처리하는 예외다. 여기서는 MDC 복구만 본다
            }
            leaked.set(MDC.get("traceId"));
        });

        // then
        assertThat(leaked.get()).isNull();
    }

    private static void runOnOtherThread(Runnable runnable) throws InterruptedException {
        Thread thread = new Thread(runnable);
        thread.start();
        thread.join();
    }
}
