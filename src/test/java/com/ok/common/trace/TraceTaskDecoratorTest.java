package com.ok.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceTaskDecoratorTest {

    private final TraceTaskDecorator sut = new TraceTaskDecorator();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        MDC.clear();
    }

    @Test
    void 데코레이트한_작업은_제출_시점의_traceId를_본다() throws Exception {
        // given
        MDC.put(TraceContext.MDC_KEY, "trace-1");
        String[] observed = new String[1];
        Runnable decorated = sut.decorate(() -> observed[0] = MDC.get(TraceContext.MDC_KEY));
        MDC.clear();

        // when
        executor.submit(decorated).get();

        // then
        assertThat(observed[0]).isEqualTo("trace-1");
    }

    @Test
    void 작업이_예외로_끝나도_다음_작업에_traceId가_남지_않는다() throws Exception {
        // given
        MDC.put(TraceContext.MDC_KEY, "trace-1");
        Runnable failing = sut.decorate(() -> {
            throw new IllegalStateException("의도된 실패");
        });
        MDC.clear();

        // when
        Future<?> failed = executor.submit(failing);
        Future<String> next = executor.submit(() -> MDC.get(TraceContext.MDC_KEY));

        // then
        assertThatThrownBy(failed::get).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(next.get()).isNull();
    }

    @Test
    void traceId가_없으면_MDC가_빈_채로_실행되고_예외도_없다() throws Exception {
        // given
        String[] observed = {"sentinel"};
        Runnable decorated = sut.decorate(() -> observed[0] = MDC.get(TraceContext.MDC_KEY));

        // when
        executor.submit(decorated).get();

        // then
        assertThat(observed[0]).isNull();
    }

    @Test
    void 작업이_끝나면_실행_스레드에_원래_있던_MDC_값이_복원된다() throws Exception {
        // given
        executor.submit(() -> MDC.put("worker", "worker-own")).get();
        MDC.put(TraceContext.MDC_KEY, "trace-1");
        Runnable decorated = sut.decorate(() -> { });
        MDC.clear();

        // when
        executor.submit(decorated).get();
        Future<String[]> after = executor.submit(
                () -> new String[]{MDC.get("worker"), MDC.get(TraceContext.MDC_KEY)});

        // then
        String[] values = after.get();
        assertThat(values[0]).isEqualTo("worker-own");
        assertThat(values[1]).isNull();
    }
}
