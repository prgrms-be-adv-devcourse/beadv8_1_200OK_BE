package com.ok.refund.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RefundStepTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 10, 0);

    private static RefundStep stepWith(RefundStepStatus status) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(1);
        String reason = status == RefundStepStatus.STOPPED ? "처음 이유" : null;
        return RefundStep.restore(1L, 10L, RefundStepType.WALLET, status, 1, nextCheckAt, reason, NOW);
    }

    @Test
    void 시작하면_시도_중이고_다음_볼_시각은_1분_뒤다() {
        // given
        // when
        RefundStep sut = RefundStep.start(10L, RefundStepType.ORDER, NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getId()).isNull();
            softly.assertThat(sut.getRefundId()).isEqualTo(10L);
            softly.assertThat(sut.getType()).isEqualTo(RefundStepType.ORDER);
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(1));
            softly.assertThat(sut.getCreatedAt()).isEqualTo(NOW);
        });
    }

    static Stream<Arguments> 끝난_단계와_전이() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (RefundStepStatus status : new RefundStepStatus[]{
                RefundStepStatus.SUCCEEDED, RefundStepStatus.REFUSED, RefundStepStatus.STOPPED}) {
            builder.add(Arguments.of(status, "begin", (Function<RefundStep, Boolean>) s -> s.begin(NOW)));
            builder.add(Arguments.of(status, "awaitResult", (Function<RefundStep, Boolean>) s -> s.awaitResult(NOW)));
            builder.add(Arguments.of(status, "succeed", (Function<RefundStep, Boolean>) RefundStep::succeed));
            builder.add(Arguments.of(status, "refuse", (Function<RefundStep, Boolean>) s -> s.refuse("거절")));
            builder.add(Arguments.of(status, "failForRetry", (Function<RefundStep, Boolean>) s -> s.failForRetry("실패", NOW)));
        }
        return builder.build();
    }

    @ParameterizedTest(name = "{0} 에서 {1}")
    @MethodSource("끝난_단계와_전이")
    void 끝난_단계는_다른_상태로_바뀌지_않는다(RefundStepStatus status, String name,
                           Function<RefundStep, Boolean> transition) {
        // given
        RefundStep sut = stepWith(status);

        // when
        boolean changed = transition.apply(sut);

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(status);
            softly.assertThat(sut.getNextCheckAt()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("성공이나_거절한_상태")
    void 성공이나_거절한_단계는_멈출_수_없다(RefundStepStatus status) {
        // given
        RefundStep sut = stepWith(status);

        // when
        boolean changed = sut.stop("멈춤");

        // then
        assertThat(changed).isFalse();
        assertThat(sut.getStatus()).isEqualTo(status);
    }

    static Stream<RefundStepStatus> 성공이나_거절한_상태() {
        return Stream.of(RefundStepStatus.SUCCEEDED, RefundStepStatus.REFUSED);
    }

    @Test
    void 시도_중에_결과를_기다리면_30분_뒤에_다시_본다() {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.WALLET, NOW);
        LocalDateTime later = NOW.plusSeconds(5);

        // when
        boolean changed = sut.awaitResult(later);

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(later.plusMinutes(30));
        });
    }

    @Test
    void 결과_대기_전에_성공해도_성공으로_남는다() {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.WALLET, NOW);
        sut.succeed();

        // when
        boolean changed = sut.awaitResult(NOW.plusSeconds(1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
            softly.assertThat(sut.getNextCheckAt()).isNull();
        });
    }

    @Test
    void 멈춘_단계에_다른_이유로_멈추면_이유만_바뀐다() {
        // given
        RefundStep sut = stepWith(RefundStepStatus.STOPPED);

        // when
        boolean changed = sut.stop("다른 이유");
        boolean sameAgain = sut.stop("다른 이유");

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sameAgain).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(sut.getLastReason()).isEqualTo("다른 이유");
            softly.assertThat(sut.isStoppedWith("다른 이유")).isTrue();
            softly.assertThat(sut.isStoppedWith("처음 이유")).isFalse();
        });
    }

    @Test
    void 실패하면_할_차례로_돌아가고_다시_시작하면_시도_횟수가_는다() {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.PRODUCT, NOW);
        LocalDateTime failedAt = NOW.plusSeconds(3);

        // when
        boolean failed = sut.failForRetry("상품 서버 오류", failedAt);

        // then
        assertSoftly(softly -> {
            softly.assertThat(failed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(sut.getLastReason()).isEqualTo("상품 서버 오류");
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(failedAt.plusMinutes(1));
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
        });

        // when
        LocalDateTime retryAt = failedAt.plusMinutes(1);
        boolean began = sut.begin(retryAt);
        boolean beganAgain = sut.begin(retryAt);

        // then
        assertSoftly(softly -> {
            softly.assertThat(began).isTrue();
            softly.assertThat(beganAgain).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(2);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(retryAt.plusMinutes(1));
        });
    }

    @ParameterizedTest
    @MethodSource("비어_있는_이유")
    void 이유가_비면_거부한다(String reason) {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.ORDER, NOW);

        // when
        // then
        assertThatThrownBy(() -> sut.stop(reason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이유는 비어 있을 수 없습니다.");
        assertThatThrownBy(() -> sut.refuse(reason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이유는 비어 있을 수 없습니다.");
        assertThatThrownBy(() -> sut.failForRetry(reason, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이유는 비어 있을 수 없습니다.");
    }

    static Stream<String> 비어_있는_이유() {
        return Stream.of(null, "", "  ");
    }

    @Test
    void 이유가_100자를_넘으면_앞_100자만_남는다() {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.ORDER, NOW);
        String longReason = "가".repeat(RefundStep.MAX_REASON_LENGTH + 20);

        // when
        sut.stop(longReason);

        // then
        assertThat(sut.getLastReason()).isEqualTo("가".repeat(RefundStep.MAX_REASON_LENGTH));
    }
}
