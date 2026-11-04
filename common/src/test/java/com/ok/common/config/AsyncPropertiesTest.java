package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import com.ok.common.config.AsyncProperties.ExecutorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AsyncPropertiesTest {

    @Test
    void 정상_값이면_그대로_담긴다() {
        // given
        ExecutorProperties external = new ExecutorProperties(false, 3, true, Duration.ofSeconds(30), "worker-");

        // when
        AsyncProperties sut = new AsyncProperties("order-external-api", Map.of("order-external-api", external));

        // then
        assertThat(sut).isNotNull();
        ExecutorProperties actual = sut.executors().get("order-external-api");
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.defaultExecutor()).isEqualTo("order-external-api");
            softly.assertThat(actual.virtualThreads()).isFalse();
            softly.assertThat(actual.concurrencyLimit()).isEqualTo(3);
            softly.assertThat(actual.rejectTasksWhenLimitReached()).isTrue();
            softly.assertThat(actual.taskTerminationTimeout()).isEqualTo(Duration.ofSeconds(30));
            softly.assertThat(actual.threadNamePrefix()).isEqualTo("worker-");
        });
    }

    // yaml을 가져오지 않는 테스트나 모듈 단독 실행에서도 앱이 떠야 한다
    @Test
    void 설정이_없어도_event_실행기는_기본값으로_있다() {
        // when
        AsyncProperties sut = new AsyncProperties("event", null);

        // then
        ExecutorProperties event = sut.executors().get("event");
        assertThat(event).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(event.virtualThreads()).isTrue();
            softly.assertThat(event.concurrencyLimit()).isEqualTo(5);
            softly.assertThat(event.rejectTasksWhenLimitReached()).isFalse();
            softly.assertThat(event.taskTerminationTimeout()).isEqualTo(Duration.ofSeconds(10));
            softly.assertThat(event.threadNamePrefix()).isNull();
        });
    }

    @Test
    void event_설정이_있으면_기본값이_아니라_그_값을_쓴다() {
        // given
        ExecutorProperties custom = new ExecutorProperties(true, 2, false, Duration.ofSeconds(1), "e-");

        // when
        AsyncProperties sut = new AsyncProperties("event", Map.of("event", custom));

        // then
        assertThat(sut.executors().get("event")).isEqualTo(custom);
    }

    @Test
    void 기본_실행기가_설정에_없으면_만들_때_실패한다() {
        // when & then
        assertThatThrownBy(() -> new AsyncProperties("missing", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.async.executors.missing");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_기본_실행기_이름")
    void 기본_실행기_이름이_비어_있으면_만들_때_실패한다(String defaultExecutor) {
        // when & then
        assertThatThrownBy(() -> new AsyncProperties(defaultExecutor, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-executor");
    }

    static Stream<String> 비어_있는_기본_실행기_이름() {
        return Stream.of(null, "", " ");
    }

    // 0은 "대기 없이 즉시 종료"로 쓸 수 있어 허용한다
    @Test
    void 종료_대기_시간이_0이어도_만들_수_있다() {
        // when
        ExecutorProperties sut = new ExecutorProperties(true, 1, false, Duration.ZERO, null);

        // then
        assertThat(sut.taskTerminationTimeout()).isEqualTo(Duration.ZERO);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("잘못된_실행기_값")
    void 실행기_값이_잘못되면_만들_때_실패한다(String name, int limit, Duration timeout, String prefix, String message) {
        // when & then
        assertThatThrownBy(() -> new ExecutorProperties(true, limit, false, timeout, prefix))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    static Stream<Arguments> 잘못된_실행기_값() {
        return Stream.of(
                Arguments.of("동시 실행 상한이 0", 0, Duration.ofSeconds(10), "event-", "concurrency-limit"),
                Arguments.of("동시 실행 상한이 음수", -1, Duration.ofSeconds(10), "event-", "concurrency-limit"),
                Arguments.of("종료 대기 시간이 없음", 5, null, "event-", "task-termination-timeout"),
                Arguments.of("종료 대기 시간이 음수", 5, Duration.ofSeconds(-1), "event-", "task-termination-timeout"),
                Arguments.of("스레드 이름 앞부분이 공백", 5, Duration.ofSeconds(10), " ", "thread-name-prefix")
        );
    }
}
