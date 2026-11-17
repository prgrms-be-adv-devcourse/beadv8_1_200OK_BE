package com.ok.order.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class OrderMessageFailureTest {

    @Test
    void 실패를_기록하면_대기_상태이고_긴_값은_칸_길이로_잘린다() {
        // when
        OrderMessageFailure sut = OrderMessageFailure.record("g".repeat(300), "order.test.v1", 0, 7L,
                "k".repeat(300), "{}", "r".repeat(1_200));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(OrderMessageFailureStatus.PENDING);
            softly.assertThat(sut.getConsumerGroup()).hasSize(OrderMessageFailure.MAX_CONSUMER_GROUP_LENGTH);
            softly.assertThat(sut.getMessageKey()).hasSize(OrderMessageFailure.MAX_MESSAGE_KEY_LENGTH);
            softly.assertThat(sut.getFailureReason()).hasSize(OrderMessageFailure.MAX_FAILURE_REASON_LENGTH);
        });
    }

    @Test
    void 키와_원문이_없어도_기록한다() {
        // when
        OrderMessageFailure sut = OrderMessageFailure.record("order.test", "order.test.v1", 0, 7L, null, null, "원인");

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getMessageKey()).isNull();
            softly.assertThat(sut.getPayload()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("빠진_필수_값")
    void 그룹_토픽_실패_이유가_없으면_기록할_수_없다(String group, String topic, String reason) {
        // when & then
        assertThatThrownBy(() -> OrderMessageFailure.record(group, topic, 0, 7L, null, null, reason))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> 빠진_필수_값() {
        return Stream.of(
                Arguments.of(" ", "order.test.v1", "원인"),
                Arguments.of(null, "order.test.v1", "원인"),
                Arguments.of("order.test", null, "원인"),
                Arguments.of("order.test", "order.test.v1", ""));
    }
}
