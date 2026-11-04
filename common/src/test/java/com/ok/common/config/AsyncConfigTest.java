package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 비동기 실행기 설정을 검증한다. DB 없이 컨텍스트만 띄워 실행기의 성격(가상 스레드, 상한, 거절)과
 * 이름별 설정, 기본 실행기 선택을 확인한다.
 */
class AsyncConfigTest {

    private static final long WAIT_SECONDS = 5;
    private static final String EXTERNAL_API = "order-external-api";

    // 앱이 import하는 것과 같은 방식으로 common-async.yaml을 읽는다
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.import=classpath:common-async.yaml")
            .withUserConfiguration(AsyncConfig.class, AsyncProbe.class);

    // 모듈이 하는 것처럼 실행기를 한 줄로 선언하는 설정
    // @Configuration을 붙이지 않는다. common의 테스트 클래스는 다른 모듈의 테스트 클래스패스에도 노출되어,
    // 붙이면 그 모듈의 통합 테스트가 컴포넌트 스캔으로 이 빈을 등록하고 yml에 없는 설정 때문에 기동이 실패한다
    static class OrderAsyncConfig {

        @Bean(EXTERNAL_API)
        SimpleAsyncTaskExecutor orderExternalApiExecutor(AsyncExecutorFactory factory) {
            return factory.create(EXTERNAL_API);
        }
    }

    // 값이 빠지거나 바뀌면 리스너가 DB 커넥션을 다 차지하거나 로그에서 스레드를 구분하지 못한다
    @Test
    void common_async_yaml의_설정이_적용된다() {
        runner.run(context -> {
            // when
            AsyncProperties sut = context.getBean(AsyncProperties.class);

            // then
            assertThat(sut).isNotNull();
            AsyncProperties.ExecutorProperties event = sut.executors().get("event");
            assertThat(event).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(sut.defaultExecutor()).isEqualTo("event");
                softly.assertThat(event.concurrencyLimit()).isEqualTo(5);
                softly.assertThat(event.rejectTasksWhenLimitReached()).isFalse();
                softly.assertThat(event.taskTerminationTimeout()).isEqualTo(Duration.ofSeconds(10));
                softly.assertThat(event.threadNamePrefix()).isEqualTo("event-");
            });
        });
    }

    // 모듈 yml이 실행기를 추가하거나 일부 값만 바꿔도 나머지는 그대로여야 한다 (List였다면 통째로 바뀐다)
    @Test
    void 모듈_설정은_같은_이름의_필드만_덮어쓰고_나머지는_유지한다() {
        runner.withPropertyValues(
                "app.async.executors." + EXTERNAL_API + ".concurrency-limit=3",
                "app.async.executors.event.concurrency-limit=2"
        ).run(context -> {
            // when
            AsyncProperties sut = context.getBean(AsyncProperties.class);

            // then
            assertThat(sut).isNotNull();
            AsyncProperties.ExecutorProperties event = sut.executors().get("event");
            AsyncProperties.ExecutorProperties external = sut.executors().get(EXTERNAL_API);
            assertThat(event).isNotNull();
            assertThat(external).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(event.concurrencyLimit()).isEqualTo(2);
                softly.assertThat(event.threadNamePrefix()).isEqualTo("event-");
                softly.assertThat(event.taskTerminationTimeout()).isEqualTo(Duration.ofSeconds(10));
                softly.assertThat(external.concurrencyLimit()).isEqualTo(3);
                softly.assertThat(external.threadNamePrefix()).isNull();
            });
        });
    }

    @Test
    void 기본_실행기는_event_빈이고_상한은_설정값이다() {
        runner.withPropertyValues("app.async.executors.event.concurrency-limit=3").run(context -> {
            // when
            SimpleAsyncTaskExecutor sut = context.getBean("event", SimpleAsyncTaskExecutor.class);

            // then
            assertThat(sut.getConcurrencyLimit()).isEqualTo(3);
        });
    }

    @Test
    void 작업은_접두사가_붙은_가상_스레드에서_실행된다() {
        runner.run(context -> {
            // given
            SimpleAsyncTaskExecutor sut = context.getBean("event", SimpleAsyncTaskExecutor.class);
            CompletableFuture<Thread> ran = new CompletableFuture<>();

            // when
            sut.execute(() -> ran.complete(Thread.currentThread()));

            // then
            Thread thread = ran.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertSoftly(softly -> {
                softly.assertThat(thread.isVirtual()).isTrue();
                softly.assertThat(thread.getName()).startsWith("event-");
            });
        });
    }

    @Test
    void 모듈이_선언한_실행기는_yml_설정과_이름으로_만들어진다() {
        runner.withUserConfiguration(OrderAsyncConfig.class)
                .withPropertyValues("app.async.executors." + EXTERNAL_API + ".concurrency-limit=3")
                .run(context -> {
                    // given
                    SimpleAsyncTaskExecutor sut = context.getBean(EXTERNAL_API, SimpleAsyncTaskExecutor.class);
                    CompletableFuture<Thread> ran = new CompletableFuture<>();

                    // when
                    sut.execute(() -> ran.complete(Thread.currentThread()));

                    // then
                    Thread thread = ran.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    assertSoftly(softly -> {
                        softly.assertThat(sut.getConcurrencyLimit()).isEqualTo(3);
                        softly.assertThat(thread.isVirtual()).isTrue();
                        // 접두사를 지정하지 않으면 실행기 이름으로 만든다
                        softly.assertThat(thread.getName()).startsWith(EXTERNAL_API + "-");
                    });
                });
    }

    // 운영에서 처음 쓸 때 늦게 발견되지 않도록, 모듈이 선언한 이름이 yml에 없으면 기동할 때 실패해야 한다
    @Test
    void 모듈이_선언한_실행기가_yml에_없으면_기동이_실패한다() {
        runner.withUserConfiguration(OrderAsyncConfig.class).run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("app.async.executors." + EXTERNAL_API);
        });
    }

    @Test
    void 기본_실행기가_설정에_없으면_기동이_실패한다() {
        runner.withPropertyValues("app.async.default-executor=missing").run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("app.async.executors.missing");
        });
    }

    @Test
    void 기본_실행기_빈이_없으면_기동이_실패한다() {
        // 설정에는 있지만 그 이름으로 선언한 빈이 없는 경우
        runner.withPropertyValues(
                "app.async.default-executor=" + EXTERNAL_API,
                "app.async.executors." + EXTERNAL_API + ".concurrency-limit=3"
        ).run(context -> {
            // then
            assertThat(context).hasFailed();
        });
    }

    // @ApplicationModuleListener 안의 @Async가 이 실행기를 쓴다. 호출한 스레드와 다른 가상 스레드에서 실행돼야 한다
    @Test
    void 이름_없는_Async_메서드는_기본_실행기_스레드에서_실행된다() {
        runner.run(context -> {
            // given
            AsyncProbe sut = context.getBean(AsyncProbe.class);
            CompletableFuture<Thread> ran = new CompletableFuture<>();

            // when
            sut.recordThread(ran);

            // then
            Thread thread = ran.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertSoftly(softly -> {
                softly.assertThat(thread).isNotSameAs(Thread.currentThread());
                softly.assertThat(thread.isVirtual()).isTrue();
                softly.assertThat(thread.getName()).startsWith("event-");
            });
        });
    }

    @Test
    void 이름을_지정한_Async_메서드는_그_실행기에서_실행된다() {
        runner.withUserConfiguration(OrderAsyncConfig.class)
                .withPropertyValues("app.async.executors." + EXTERNAL_API + ".thread-name-prefix=api-")
                .run(context -> {
                    // given
                    AsyncProbe sut = context.getBean(AsyncProbe.class);
                    CompletableFuture<Thread> ran = new CompletableFuture<>();

                    // when
                    sut.recordThreadOnExternalApi(ran);

                    // then
                    Thread thread = ran.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    assertSoftly(softly -> {
                        softly.assertThat(thread.isVirtual()).isTrue();
                        softly.assertThat(thread.getName()).startsWith("api-");
                    });
                });
    }

    // default-executor를 바꾸면 이름 없는 @Async가 그 실행기로 간다
    @Test
    void 기본_실행기를_바꾸면_이름_없는_Async가_그_실행기로_간다() {
        runner.withUserConfiguration(OrderAsyncConfig.class)
                .withPropertyValues(
                        "app.async.default-executor=" + EXTERNAL_API,
                        "app.async.executors." + EXTERNAL_API + ".thread-name-prefix=api-")
                .run(context -> {
                    // given
                    AsyncProbe sut = context.getBean(AsyncProbe.class);
                    CompletableFuture<Thread> ran = new CompletableFuture<>();

                    // when
                    sut.recordThread(ran);

                    // then
                    Thread thread = ran.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    assertThat(thread.getName()).startsWith("api-");
                });
    }

    // 실제 앱에는 @EnableScheduling이 만든 taskScheduler 같은 다른 Executor 빈이 함께 있다. 그래도 기동돼야 한다
    @Test
    void 다른_Executor_빈이_있어도_기동된다() {
        runner.withBean("taskScheduler", ThreadPoolTaskScheduler.class).run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(SimpleAsyncTaskExecutor.class)).hasSize(1);
        });
    }

    @Test
    void 거절을_켜면_상한에_걸린_작업은_거절된다() {
        runner.withPropertyValues(
                "app.async.executors.event.concurrency-limit=1",
                "app.async.executors.event.reject-tasks-when-limit-reached=true"
        ).run(context -> {
            // given
            SimpleAsyncTaskExecutor sut = context.getBean("event", SimpleAsyncTaskExecutor.class);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            sut.execute(() -> {
                started.countDown();
                awaitQuietly(release);
            });
            assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // when & then
            try {
                assertThatThrownBy(() -> sut.execute(() -> { }))
                        .isInstanceOf(TaskRejectedException.class);
            } finally {
                release.countDown();
            }
        });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static class AsyncProbe {

        @Async
        public void recordThread(CompletableFuture<Thread> ran) {
            ran.complete(Thread.currentThread());
        }

        @Async(EXTERNAL_API)
        public void recordThreadOnExternalApi(CompletableFuture<Thread> ran) {
            ran.complete(Thread.currentThread());
        }
    }
}
