package com.ok.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ErrorKeyThrottleTurboFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private LoggerContext context;
    private ListAppender<ILoggingEvent> appender;
    private ErrorKeyThrottleTurboFilter sut;

    @BeforeEach
    void setUp() {
        LogDropCounter.reset();
        context = new LoggerContext();
        sut = new ErrorKeyThrottleTurboFilter();
        sut.setContext(context);
        sut.setLimit(3);
        sut.setWindowSeconds(60);
        sut.setClock(now::get);
        sut.start();
        context.addTurboFilter(sut);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        sut.stop();
    }

    @Test
    void 같은_에러_키가_창_안에서_상한을_넘으면_넘은_건을_버리고_창이_끝난_뒤_N건_생략_요약을_남긴다() {
        // given
        Logger logger = context.getLogger("com.ok.wallet.WalletService");

        // when
        for (int i = 0; i < 10; i++) {
            logger.warn("결제 실패", new IllegalStateException("잔액 부족 " + i));
        }
        int passedInWindow = appender.list.size();
        now.addAndGet(61_000);
        sut.sweep();

        // then
        assertSoftly(softly -> {
            softly.assertThat(passedInWindow).isEqualTo(3);
            softly.assertThat(appender.list).hasSize(4);
            softly.assertThat(appender.list.get(3).getLevel()).isEqualTo(Level.WARN);
            softly.assertThat(appender.list.get(3).getFormattedMessage()).contains("같은 에러 7건 생략").contains("IllegalStateException");
            softly.assertThat(LogDropCounter.count(LogDropCounter.Reason.THROTTLED)).isEqualTo(7);
        });
    }

    @Test
    void 창이_끝난_뒤_같은_키가_다시_오면_그때_요약을_남기고_새_창으로_통과시킨다() {
        // given
        Logger logger = context.getLogger("com.ok.wallet.WalletService");
        for (int i = 0; i < 5; i++) {
            logger.warn("결제 실패", new IllegalStateException("x"));
        }
        now.addAndGet(61_000);

        // when
        logger.warn("결제 실패", new IllegalStateException("x"));

        // then
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .hasSize(5)
                .anyMatch(message -> message.contains("같은 에러 2건 생략"));
    }

    @Test
    void 키가_다르면_따로_센다() {
        // given
        Logger wallet = context.getLogger("com.ok.wallet.WalletService");
        Logger order = context.getLogger("com.ok.order.OrderService");

        // when
        for (int i = 0; i < 3; i++) {
            wallet.warn("실패", new IllegalStateException());
            wallet.warn("실패", new IllegalArgumentException());
            order.warn("실패", new IllegalStateException());
        }

        // then
        assertThat(appender.list).hasSize(9);
    }

    @Test
    void WARN_미만은_횟수와_상관없이_통과한다() {
        // given
        Logger logger = context.getLogger("com.ok.order.OrderService");

        // when
        for (int i = 0; i < 20; i++) {
            logger.info("주문 처리 {}", i);
        }

        // then
        assertThat(appender.list).hasSize(20);
    }

    @Test
    void 추적하는_키가_가득_차면_새_키는_추적하지_않고_통과시킨다() {
        // given
        sut.setMaxKeys(2);
        Logger logger = context.getLogger("com.ok.order.OrderService");

        // when
        for (int i = 0; i < 10; i++) {
            logger.warn("서로 다른 틀 " + i);
        }

        // then
        assertThat(appender.list).hasSize(10);
    }

    @Test
    void 레벨_확인_호출은_횟수에_세지_않는다() {
        // given
        Logger logger = context.getLogger("com.ok.order.OrderService");

        // when
        for (int i = 0; i < 10; i++) {
            logger.isErrorEnabled();
        }
        logger.error("실패");

        // then
        assertThat(appender.list).hasSize(1);
    }

    @Test
    void 인자_끝에_예외를_넘겨도_예외_클래스로_묶는다() {
        // given
        Logger logger = context.getLogger("com.ok.order.OrderService");

        // when
        for (int i = 0; i < 10; i++) {
            logger.error("주문 {} 실패", i, new IllegalStateException("원인 " + i));
        }

        // then
        assertThat(appender.list).hasSize(3);
    }
}
