package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import com.ok.common.config.AsyncProperties.ExecutorProperties;
import com.ok.common.config.AsyncProperties.ExecutorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AsyncPropertiesTest {

    @Test
    void 정상_값이면_그대로_담긴다() {
        // given
        ExecutorProperties external = new ExecutorProperties(ExecutorType.PLATFORM, 3, 4, 50, Duration.ofSeconds(30), "worker-");

        // when
        AsyncProperties sut = new AsyncProperties("order-external-api", Map.of("order-external-api", external));

        // then
        assertThat(sut).isNotNull();
        ExecutorProperties actual = sut.executors().get("order-external-api");
        assertThat(actual).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.defaultExecutor()).isEqualTo("order-external-api");
            softly.assertThat(actual.type()).isEqualTo(ExecutorType.PLATFORM);
            softly.assertThat(actual.concurrencyLimit()).isEqualTo(3);
            softly.assertThat(actual.poolSize()).isEqualTo(4);
            softly.assertThat(actual.queueCapacity()).isEqualTo(50);
            softly.assertThat(actual.taskTerminationTimeout()).isEqualTo(Duration.ofSeconds(30));
            softly.assertThat(actual.threadNamePrefix()).isEqualTo("worker-");
        });
    }

    // yaml을 가져오지 않는 테스트나 모듈 단독 실행에서도 앱이 떠야 한다. 리스너가 요청 스레드를 막지 않도록 대기열이 있는 platform이다
    @Test
    void 설정이_없어도_event_실행기는_대기열이_있는_platform_기본값으로_있다() {
        // when
        AsyncProperties sut = new AsyncProperties("event", null);

        // then
        ExecutorProperties event = sut.executors().get("event");
        assertThat(event).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(event.type()).isEqualTo(ExecutorType.PLATFORM);
            softly.assertThat(event.poolSize()).isEqualTo(8);
            softly.assertThat(event.queueCapacity()).isEqualTo(1000);
            softly.assertThat(event.taskTerminationTimeout()).isEqualTo(Duration.ofSeconds(10));
            softly.assertThat(event.threadNamePrefix()).isNull();
        });
    }

    @Test
    void event_설정이_있으면_기본값이_아니라_그_값을_쓴다() {
        // given
        ExecutorProperties custom = new ExecutorProperties(ExecutorType.VIRTUAL, 2, 8, 1000, Duration.ofSeconds(1), "e-");

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
        ExecutorProperties sut = new ExecutorProperties(ExecutorType.VIRTUAL, 1, 1, 1, Duration.ZERO, null);

        // then
        assertThat(sut.taskTerminationTimeout()).isEqualTo(Duration.ZERO);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("잘못된_실행기_값")
    void 실행기_값이_잘못되면_만들_때_실패한다(String name, ExecutorType type, int limit, int poolSize, int queueCapacity,
                              Duration timeout, String prefix, String message) {
        // when & then
        assertThatThrownBy(() -> new ExecutorProperties(type, limit, poolSize, queueCapacity, timeout, prefix))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    static Stream<Arguments> 잘못된_실행기_값() {
        Duration ok = Duration.ofSeconds(10);
        ExecutorType v = ExecutorType.VIRTUAL;
        return Stream.of(
                Arguments.of("종류가 없음", null, 5, 8, 1000, ok, "event-", "type"),
                Arguments.of("동시 실행 상한이 0", v, 0, 8, 1000, ok, "event-", "concurrency-limit"),
                Arguments.of("동시 실행 상한이 음수", v, -1, 8, 1000, ok, "event-", "concurrency-limit"),
                Arguments.of("스레드 수가 0", v, 5, 0, 1000, ok, "event-", "pool-size"),
                Arguments.of("대기열 크기가 0", v, 5, 8, 0, ok, "event-", "queue-capacity"),
                Arguments.of("종료 대기 시간이 없음", v, 5, 8, 1000, null, "event-", "task-termination-timeout"),
                Arguments.of("종료 대기 시간이 음수", v, 5, 8, 1000, Duration.ofSeconds(-1), "event-", "task-termination-timeout"),
                Arguments.of("스레드 이름 앞부분이 공백", v, 5, 8, 1000, ok, " ", "thread-name-prefix")
        );
    }
}
