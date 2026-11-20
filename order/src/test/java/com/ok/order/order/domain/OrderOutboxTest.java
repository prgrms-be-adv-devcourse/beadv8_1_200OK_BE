package com.ok.order.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class OrderOutboxTest {

    @Test
    void 보낼_메시지는_보낼_차례로_시작한다() {
        // when
        OrderOutbox sut = OrderOutbox.pending("order.test.v1", "10", "{\"orderId\":10}");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.isPending()).isTrue();
            softly.assertThat(sut.getAttemptCount()).isZero();
            softly.assertThat(sut.getSentAt()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("비었거나_너무_긴_값")
    void 토픽_키_본문이_비었거나_칸보다_길면_만들_수_없다(String topic, String key, String payload) {
        // when & then
        assertThatThrownBy(() -> OrderOutbox.pending(topic, key, payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> 비었거나_너무_긴_값() {
        return Stream.of(
                Arguments.of(null, "10", "{}"),
                Arguments.of(" ", "10", "{}"),
                Arguments.of("order.test.v1", "", "{}"),
                Arguments.of("order.test.v1", "10", null),
                Arguments.of("t".repeat(250), "10", "{}"),
                Arguments.of("order.test.v1", "k".repeat(256), "{}"));
    }

    @Test
    void 발송_실패를_기록하면_시도_횟수가_늘고_원인은_칸_길이로_잘린다() {
        // given
        OrderOutbox sut = OrderOutbox.pending("order.test.v1", "10", "{}");

        // when
        sut.recordFailure("e".repeat(600));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.isPending()).isTrue();
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
            softly.assertThat(sut.getLastError()).hasSize(OrderOutbox.MAX_ERROR_LENGTH);
        });
    }
}
