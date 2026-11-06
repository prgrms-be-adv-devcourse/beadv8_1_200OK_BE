package com.ok.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.AppenderBase;

class CountingAsyncAppenderTest {

    private static final int QUEUE_SIZE = 4;

    @BeforeEach
    void resetCounter() {
        LogDropCounter.reset();
    }

    // 비동기 appender는 이벤트를 큐에 넣기 전에 MDC를 복사하므로, 로깅 시스템 없이 만든 컨텍스트에는 어댑터를 달아 줘야 한다
    private static LoggerContext newContext() {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        return context;
    }

    @Test
    void 큐가_차면_요청_스레드를_막지_않고_초과분을_버리며_버린_건수를_센다() throws InterruptedException {
        // given
        LoggerContext context = newContext();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();
        AppenderBase<ILoggingEvent> slowAppender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                written.incrementAndGet();
            }
        };
        slowAppender.setContext(context);
        slowAppender.start();
        CountingAsyncAppender sut = new CountingAsyncAppender();
        sut.setContext(context);
        sut.setQueueSize(QUEUE_SIZE);
        sut.setDiscardingThreshold(0);
        sut.setNeverBlock(true);
        sut.addAppender(slowAppender);
        sut.start();
        Logger logger = context.getLogger("test");
        logger.setLevel(Level.INFO);
        logger.addAppender(sut);
        int sent = 50;

        // when
        long startedAt = System.nanoTime();
        for (int i = 0; i < sent; i++) {
            logger.info("메시지 {}", i);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        release.countDown();
        sut.stop();

        // then
        long dropped = LogDropCounter.count(LogDropCounter.Reason.QUEUE_FULL);
        assertSoftly(softly -> {
            softly.assertThat(elapsedMillis).as("가득 찬 큐가 호출 스레드를 막지 않는다").isLessThan(5_000);
            softly.assertThat(dropped).isPositive();
            softly.assertThat(written.get() + dropped).as("쓴 건과 버린 건의 합이 보낸 건과 같다").isEqualTo(sent);
            softly.assertThat(written.get()).as("큐 크기를 크게 넘는 건은 쓰지 못한다").isLessThan(sent / 2);
        });
    }

    @Test
    void 큐에_여유가_있으면_버리지_않는다() {
        // given
        LoggerContext context = newContext();
        AtomicInteger written = new AtomicInteger();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                written.incrementAndGet();
            }
        };
        appender.setContext(context);
        appender.start();
        CountingAsyncAppender sut = new CountingAsyncAppender();
        sut.setContext(context);
        sut.setQueueSize(100);
        sut.setNeverBlock(true);
        sut.addAppender(appender);
        sut.start();
        Logger logger = context.getLogger("test");
        logger.addAppender(sut);

        // when
        for (int i = 0; i < 10; i++) {
            logger.info("메시지 {}", i);
        }
        sut.stop();

        // then
        assertThat(written.get()).isEqualTo(10);
        assertThat(LogDropCounter.count(LogDropCounter.Reason.QUEUE_FULL)).isZero();
    }
}
