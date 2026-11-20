package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ok.common.config.AsyncProperties.ExecutorType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 비동기 실행기 설정을 검증한다. DB 없이 컨텍스트만 띄워 실행기의 종류(일반 스레드 풀, 가상 스레드), 대기열, 가득 찼을 때의 동작,
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
        AsyncTaskExecutor orderExternalApiExecutor(AsyncExecutorFactory factory) {
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
                softly.assertThat(event.type()).isEqualTo(ExecutorType.PLATFORM);
                softly.assertThat(event.poolSize()).isEqualTo(8);
                softly.assertThat(event.queueCapacity()).isEqualTo(1000);
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
                "app.async.executors.event.pool-size=2"
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
                softly.assertThat(event.poolSize()).isEqualTo(2);
                softly.assertThat(event.type()).isEqualTo(ExecutorType.PLATFORM);
                softly.assertThat(event.threadNamePrefix()).isEqualTo("event-");
                softly.assertThat(event.taskTerminationTimeout()).isEqualTo(Duration.ofSeconds(10));
                softly.assertThat(external.concurrencyLimit()).isEqualTo(3);
                softly.assertThat(external.type()).isEqualTo(ExecutorType.VIRTUAL);
                softly.assertThat(external.threadNamePrefix()).isNull();
            });
        });
    }

    @Test
    void event_실행기는_설정한_크기의_대기열이_있는_일반_스레드_풀이다() {
        runner.withPropertyValues(
                "app.async.executors.event.pool-size=3",
                "app.async.executors.event.queue-capacity=50"
        ).run(context -> {
            // when
            ThreadPoolTaskExecutor sut = context.getBean("event", ThreadPoolTaskExecutor.class);

            // then
            assertSoftly(softly -> {
                softly.assertThat(sut.getCorePoolSize()).isEqualTo(3);
                softly.assertThat(sut.getMaxPoolSize()).isEqualTo(3);
                softly.assertThat(sut.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(50);
            });
        });
    }

    @Test
    void event_작업은_접두사가_붙은_일반_스레드에서_실행된다() {
        runner.run(context -> {
            // given
            AsyncTaskExecutor sut = context.getBean("event", AsyncTaskExecutor.class);
            CompletableFuture<Thread> ran = new CompletableFuture<>();

            // when
            sut.execute(() -> ran.complete(Thread.currentThread()));

            // then
            Thread thread = ran.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertSoftly(softly -> {
                softly.assertThat(thread.isVirtual()).isFalse();
                softly.assertThat(thread.getName()).startsWith("event-");
            });
        });
    }

    // 가득 찼을 때 거절하면 커밋 뒤 콜백에서 호출한 쪽으로 예외가 올라가고 같은 커밋의 다른 콜백이 실행되지 않는다.
    // 그래서 거절하거나 버리지 않고, 작업을 넘긴 스레드가 기다렸다가 작업이 모두 실행되어야 한다
    @Test
    void 대기열이_가득_차면_작업을_넘긴_스레드가_기다리고_거절하지_않는다() {
        runner.withPropertyValues(
                "app.async.executors.event.pool-size=1",
                "app.async.executors.event.queue-capacity=1"
        ).run(context -> {
            // given: 스레드 1개는 실행 중이고 대기열 1칸도 찬 상태
            AsyncTaskExecutor sut = context.getBean("event", AsyncTaskExecutor.class);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger executed = new AtomicInteger();
            sut.execute(() -> {
                started.countDown();
                awaitQuietly(release);
                executed.incrementAndGet();
            });
            assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            sut.execute(executed::incrementAndGet);

            // when: 가득 찬 상태에서 세 번째 작업을 넘긴다
            AtomicBoolean returned = new AtomicBoolean();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    sut.execute(executed::incrementAndGet);
                    returned.set(true);
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });
            caller.start();
            Thread.sleep(500);
            boolean returnedWhileFull = returned.get();
            release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            waitUntil(() -> executed.get() == 3);

            // then
            assertSoftly(softly -> {
                softly.assertThat(returnedWhileFull).isFalse();
                softly.assertThat(thrown.get()).isNull();
                softly.assertThat(returned.get()).isTrue();
                softly.assertThat(executed.get()).isEqualTo(3);
            });
        });
    }

    @Test
    void 모듈이_선언한_virtual_실행기는_접두사가_붙은_가상_스레드에서_실행되고_상한을_가진다() {
        runner.withUserConfiguration(OrderAsyncConfig.class)
                .withPropertyValues("app.async.executors." + EXTERNAL_API + ".concurrency-limit=3")
                .run(context -> {
                    // given
                    AsyncTaskExecutor sut = context.getBean(EXTERNAL_API, AsyncTaskExecutor.class);
                    CompletableFuture<Thread> ran = new CompletableFuture<>();

                    // when
                    sut.execute(() -> ran.complete(Thread.currentThread()));

                    // then
                    Thread thread = ran.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    assertThat(sut).isInstanceOf(SimpleAsyncTaskExecutor.class);
                    assertSoftly(softly -> {
                        softly.assertThat(((SimpleAsyncTaskExecutor) sut).getConcurrencyLimit()).isEqualTo(3);
                        softly.assertThat(thread.isVirtual()).isTrue();
                        // 접두사를 지정하지 않으면 실행기 이름으로 만든다
                        softly.assertThat(thread.getName()).startsWith(EXTERNAL_API + "-");
                    });
                });
    }

    // virtual은 대기열이 없다. 상한이 차면 거절하지 않고 작업을 넘긴 스레드가 자리가 날 때까지 기다린다
    @Test
    void virtual_실행기는_상한이_차면_작업을_넘긴_스레드가_기다리고_거절하지_않는다() {
        runner.withUserConfiguration(OrderAsyncConfig.class)
                .withPropertyValues("app.async.executors." + EXTERNAL_API + ".concurrency-limit=1")
                .run(context -> {
                    // given: 상한 1개가 찬 상태
                    AsyncTaskExecutor sut = context.getBean(EXTERNAL_API, AsyncTaskExecutor.class);
                    CountDownLatch started = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    sut.execute(() -> {
                        started.countDown();
                        awaitQuietly(release);
                    });
                    assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

                    // when
                    AtomicBoolean returned = new AtomicBoolean();
                    AtomicReference<Throwable> thrown = new AtomicReference<>();
                    Thread caller = new Thread(() -> {
                        try {
                            sut.execute(() -> { });
                            returned.set(true);
                        } catch (Throwable e) {
                            thrown.set(e);
                        }
                    });
                    caller.start();
                    Thread.sleep(500);
                    boolean returnedWhileFull = returned.get();
                    release.countDown();
                    caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

                    // then
                    assertSoftly(softly -> {
                        softly.assertThat(returnedWhileFull).isFalse();
                        softly.assertThat(thrown.get()).isNull();
                        softly.assertThat(returned.get()).isTrue();
                    });
                });
    }

    @Test
    void type을_platform으로_바꾸면_일반_스레드_풀이_된다() {
        runner.withUserConfiguration(OrderAsyncConfig.class)
                .withPropertyValues("app.async.executors." + EXTERNAL_API + ".type=platform")
                .run(context -> {
                    // given
                    AsyncTaskExecutor sut = context.getBean(EXTERNAL_API, AsyncTaskExecutor.class);
                    CompletableFuture<Thread> ran = new CompletableFuture<>();

                    // when
                    sut.execute(() -> ran.complete(Thread.currentThread()));

                    // then
                    Thread thread = ran.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    assertThat(sut).isInstanceOf(ThreadPoolTaskExecutor.class);
                    assertThat(thread.isVirtual()).isFalse();
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

    // @ApplicationModuleListener 안의 @Async가 기본 실행기를 쓴다. 호출한 스레드와 다른 event- 스레드에서 실행돼야 한다
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
                softly.assertThat(thread.isVirtual()).isFalse();
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
            assertThat(context.getBean("event")).isInstanceOf(ThreadPoolTaskExecutor.class);
        });
    }

    // MDC에 있는 값을 비동기 스레드로 그대로 복사한다 (값을 넣는 쪽은 별도 작업)
    @Test
    void Async_작업에는_호출한_스레드의_MDC가_전달된다() {
        runner.run(context -> {
            // given
            AsyncProbe sut = context.getBean(AsyncProbe.class);
            CompletableFuture<String> ran = new CompletableFuture<>();
            MDC.put("traceId", "trace-1");

            // when
            try {
                sut.recordMdc(ran);
            } finally {
                MDC.clear();
            }

            // then
            assertThat(ran.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("trace-1");
        });
    }

    // 이벤트에는 수령인 이름·전화번호·주소 같은 개인정보가 담길 수 있어, 예외 로그에 이벤트 값이 찍히면 안 된다
    @Test
    void 비동기_예외_로그에는_파라미터_값이_아니라_타입_이름만_남는다() {
        runner.run(context -> {
            // given
            AsyncUncaughtExceptionHandler sut = context.getBean(AsyncConfigurer.class).getAsyncUncaughtExceptionHandler();
            Method method = AsyncProbe.class.getMethod("recordMdc", CompletableFuture.class);
            Logger logger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            // when
            try {
                sut.handleUncaughtException(new IllegalStateException("실패"), method, new Object[]{new SecretEvent("010-1234-5678")});
            } finally {
                logger.detachAppender(appender);
            }

            // then
            assertThat(appender.list).hasSize(1);
            String message = appender.list.get(0).getFormattedMessage();
            assertSoftly(softly -> {
                softly.assertThat(message).contains("SecretEvent");
                softly.assertThat(message).doesNotContain("010-1234-5678");
            });
        });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    // 개인정보가 든 이벤트를 흉내 낸다
    record SecretEvent(String phone) {
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

        @Async
        public void recordMdc(CompletableFuture<String> ran) {
            ran.complete(MDC.get("traceId"));
        }
    }
}
