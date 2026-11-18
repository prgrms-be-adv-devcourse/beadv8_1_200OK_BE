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
    void 시작하면_시도_중이고_다음_볼_시각은_2분_뒤다() {
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
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(2));
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
            builder.add(Arguments.of(status, "resendAfterResultTimeout",
                    (Function<RefundStep, Boolean>) s -> s.resendAfterResultTimeout(NOW.plusDays(1))));
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
    void 시도_중에_결과를_기다리면_5분_뒤에_다시_본다() {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.WALLET, NOW);
        LocalDateTime later = NOW.plusSeconds(5);

        // when
        boolean changed = sut.awaitResult(later);

        // then
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(later.plusMinutes(5));
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
    void 멈춘_단계에_다른_이유로_멈춰도_처음_이유를_지킨다() {
        // given
        RefundStep sut = RefundStep.start(1_000L, RefundStepType.WALLET, NOW);
        sut.stop("RESULT_MISMATCH");

        // when
        boolean changed = sut.stop("PAYMENT_NOT_FOUND");

        // then: 처음 멈춘 이유(결과 불일치)가 뒤에 온 보통 실패로 지워지지 않아야 되돌리기가 사람에게 넘긴다
        assertSoftly(softly -> {
            softly.assertThat(changed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(sut.getLastReason()).isEqualTo("RESULT_MISMATCH");
            softly.assertThat(sut.isStoppedWith("RESULT_MISMATCH")).isTrue();
            softly.assertThat(sut.isStoppedWith("PAYMENT_NOT_FOUND")).isFalse();
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
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(retryAt.plusMinutes(2));
        });
    }

    @Test
    void 시도_횟수를_다_쓰고_실패하면_멈추고_이유는_이번_실패다() {
        // given: 다섯 번째 시도 중
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS,
                RefundStep.MAX_ATTEMPTS, NOW.plusMinutes(2), null, NOW);

        // when
        boolean changed = sut.failForRetry("PRODUCT_API_FAILED", NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(sut.getLastReason()).isEqualTo("PRODUCT_API_FAILED");
            softly.assertThat(sut.getNextCheckAt()).isNull();
        });
    }

    @Test
    void 시도_횟수가_남았으면_실패해도_할_차례로_돌아간다() {
        // given: 네 번째 시도 중
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS,
                RefundStep.MAX_ATTEMPTS - 1, NOW.plusMinutes(2), null, NOW);

        // when
        sut.failForRetry("PRODUCT_API_FAILED", NOW);

        // then
        assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.READY);
    }

    @Test
    void 만료된_시도_중_줄을_다시_집으면_횟수가_늘고_만료_시각을_새로_잡는다() {
        // given: 만료 시각이 지금인 시도 중 줄
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                1, NOW, null, NOW.minusMinutes(2));

        // when
        boolean changed = sut.restartExpired(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(2);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(2));
        });
    }

    @Test
    void 만료되지_않은_시도_중_줄은_다시_집지_않는다() {
        // given: 만료까지 1초 남음
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                1, NOW.plusSeconds(1), null, NOW);

        // when
        boolean changed = sut.restartExpired(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isFalse();
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusSeconds(1));
        });
    }

    @Test
    void 만료된_줄이_횟수를_다_썼으면_다시_집지_않고_멈춘다() {
        // given
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                RefundStep.MAX_ATTEMPTS, NOW, null, NOW.minusMinutes(10));

        // when
        boolean changed = sut.restartExpired(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(sut.getLastReason()).isEqualTo(RefundStep.IN_PROGRESS_EXPIRED);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(RefundStep.MAX_ATTEMPTS);
        });
    }

    @ParameterizedTest
    @MethodSource("시도_중이_아닌_상태")
    void 시도_중이_아니면_만료로_보지_않는다(RefundStepStatus status) {
        // given: 다시 볼 시각이 지났어도
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, status, 1,
                status.isFinished() ? null : NOW.minusMinutes(1), null, NOW.minusMinutes(5));

        // when
        boolean changed = sut.restartExpired(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(status);
        });
    }

    @Test
    void 결과_대기를_보내고_5분이_지나면_결과_대기_시간이_지난_것으로_본다() {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.WALLET, NOW);
        sut.awaitResult(NOW);

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(sut.isResultOverdue(NOW.plusMinutes(5).minusSeconds(1))).isFalse();
            softly.assertThat(sut.isResultOverdue(NOW.plusMinutes(5))).isTrue();
        });
    }

    // 찾기 알람(#577)이 "처음부터 얼마나 기다렸나"를 재는 기준이라, 다시 보낸 뒤 다시 결과 대기가 돼도 처음 시각을 지킨다
    @Test
    void 처음_결과_대기가_될_때의_시각을_다시_보낸_뒤에도_유지한다() {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.WALLET, NOW);
        sut.awaitResult(NOW);
        LocalDateTime resentAt = NOW.plusMinutes(5);

        // when
        sut.resendAfterResultTimeout(resentAt);
        sut.awaitResult(resentAt.plusSeconds(1));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getAwaitingSince()).isEqualTo(NOW);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(resentAt.plusSeconds(1).plusMinutes(5));
        });
    }

    // 결과를 기다린 적 있는 줄을 다시 보내다 실패하면 횟수를 다 썼어도 멈추지 않고 결과 대기로 돌아간다(#577 판단 2)
    @Test
    void 이미_보낸_줄은_다시_보내다_실패해도_멈추지_않고_결과_대기로_돌아간다() {
        // given: 처음 발행을 5번째 시도에 성공했고, 결과 대기 시간이 지나 다시 보내는 중
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                RefundStep.MAX_ATTEMPTS, NOW.plusMinutes(2), null, NOW.minusMinutes(10), NOW.minusMinutes(20));

        // when
        boolean changed = sut.failForRetry("WALLET_PUBLISH_FAILED", NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(RefundStep.MAX_ATTEMPTS);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(5));
            softly.assertThat(sut.getAwaitingSince()).isEqualTo(NOW.minusMinutes(10));
            softly.assertThat(sut.getLastReason()).isEqualTo("WALLET_PUBLISH_FAILED");
        });
    }

    // 다시 보내는 중 서버가 죽어 시도 중으로 만료된 줄도 이미 보낸 줄이면 횟수를 다 썼어도 멈추지 않고 다시 집는다(#577)
    @Test
    void 이미_보낸_줄은_만료돼도_횟수를_올리지_않고_다시_집는다() {
        // given: 처음 발행을 5번째 시도에 성공했고, 다시 보내다 시도 중인 채로 만료됨
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                RefundStep.MAX_ATTEMPTS, NOW, null, NOW.minusMinutes(10), NOW.minusMinutes(20));

        // when
        boolean changed = sut.restartExpired(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(RefundStep.MAX_ATTEMPTS);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(2));
        });
    }

    // 진행할 수 없는 환불에서 이미 보낸 줄은 멈추지 않고 늦은 결과만 기다린다(#546). 다시 볼 시각을 비워 알람이 다시 뽑지 않게 한다
    @Test
    void 이미_보낸_줄은_늦은_결과만_기다리게_다시_볼_시각을_비운다() {
        // given: 기다리는 시각이 지난 결과 대기 줄
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT,
                1, NOW, null, NOW.minusMinutes(10), NOW.minusMinutes(20));

        // when
        boolean changed = sut.holdForLateResult();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getNextCheckAt()).isNull();
            softly.assertThat(sut.isResultOverdue(NOW.plusDays(1))).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("늦은_결과를_기다리게_하지_않는_줄")
    void 보낸_적_없거나_끝난_줄은_늦은_결과를_기다리게_바꾸지_않는다(RefundStepStatus status, LocalDateTime awaitingSince) {
        // given
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, status, 1,
                status.isFinished() ? null : NOW, status == RefundStepStatus.STOPPED ? "멈춤" : null, awaitingSince, NOW.minusMinutes(20));

        // when
        boolean changed = sut.holdForLateResult();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(status);
        });
    }

    static Stream<Arguments> 늦은_결과를_기다리게_하지_않는_줄() {
        return Stream.of(
                Arguments.of(RefundStepStatus.READY, null),
                Arguments.of(RefundStepStatus.IN_PROGRESS, null),
                Arguments.of(RefundStepStatus.READY, NOW.minusMinutes(10)),
                Arguments.of(RefundStepStatus.SUCCEEDED, NOW.minusMinutes(10)),
                Arguments.of(RefundStepStatus.STOPPED, NOW.minusMinutes(10)));
    }

    @Test
    void 결과_대기가_된_적_없으면_처음_기다린_시각이_없다() {
        // when
        RefundStep sut = RefundStep.start(10L, RefundStepType.WALLET, NOW);

        // then
        assertThat(sut.getAwaitingSince()).isNull();
    }

    // 시간 초과 다시 요청은 시도 횟수를 쓰지 않는다(#577 결정). 횟수를 다 쓴 줄이어도 멈추지 않고 다시 보낸다
    @Test
    void 결과_대기_시간이_지나면_횟수를_올리지_않고_시도_중으로_바꾼다() {
        // given
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT,
                RefundStep.MAX_ATTEMPTS, NOW, null, NOW.minusMinutes(30));

        // when
        boolean changed = sut.resendAfterResultTimeout(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(RefundStep.MAX_ATTEMPTS);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(2));
        });
    }

    @Test
    void 결과_대기_시간이_남았으면_다시_보내지_않는다() {
        // given: 기다리는 시각까지 1초 남음
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT,
                1, NOW.plusSeconds(1), null, NOW.minusMinutes(5));

        // when
        boolean changed = sut.resendAfterResultTimeout(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusSeconds(1));
        });
    }

    // 예: 처음 결과를 기다린 때가 어제 10:00이고 지금이 오늘 10:00(24시간 지남). 더 보내지 않고, 멈추지도 않고 늦은 답만 기다린다(#546)
    @Test
    void 처음_기다린_지_24시간이_지나면_다시_보내지_않고_늦은_결과만_기다린다() {
        // given
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT,
                1, NOW, null, NOW.minusHours(24), NOW.minusHours(25));

        // when
        boolean resent = sut.resendAfterResultTimeout(NOW);
        boolean held = sut.stopResendingAfterLimit(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(resent).isFalse();
            softly.assertThat(held).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getNextCheckAt()).isNull();
            softly.assertThat(sut.getAwaitingSince()).isEqualTo(NOW.minusHours(24));
        });
    }

    // 다시 보내는 중 "결과 대기" 기록이 실패해 시도 중으로 만료된 줄. 처음 기다린 지 24시간이 지났으면 다시 집지 않는다(한도 우회 막기)
    @Test
    void 처음_기다린_지_24시간이_지난_만료된_시도_중_줄은_다시_집지_않고_늦은_결과만_기다린다() {
        // given
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET_REVERT, RefundStepStatus.IN_PROGRESS,
                1, NOW, null, NOW.minusHours(24), NOW.minusHours(25));

        // when
        boolean changed = sut.restartExpired(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getNextCheckAt()).isNull();
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
        });
    }

    // 예: 처음 기다린 지 23시간 59분. 한도 안이라 그대로 다시 보낸다
    @Test
    void 처음_기다린_지_24시간이_안_됐으면_한도로_멈추지_않고_다시_보낸다() {
        // given
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT,
                1, NOW, null, NOW.minusHours(24).plusMinutes(1), NOW.minusHours(25));

        // when
        boolean held = sut.stopResendingAfterLimit(NOW);
        boolean resent = sut.resendAfterResultTimeout(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(held).isFalse();
            softly.assertThat(resent).isTrue();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
        });
    }

    @ParameterizedTest
    @MethodSource("결과_대기가_아닌_상태")
    void 결과_대기가_아니면_결과_대기_시간이_지난_것으로_보지_않는다(RefundStepStatus status) {
        // given: 다시 볼 시각이 지났어도
        RefundStep sut = RefundStep.restore(1L, 10L, RefundStepType.WALLET, status, 1,
                status.isFinished() ? null : NOW.minusMinutes(1), status == RefundStepStatus.STOPPED ? "멈춤" : null, NOW.minusMinutes(5));

        // when
        boolean changed = sut.resendAfterResultTimeout(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(changed).isFalse();
            softly.assertThat(sut.getStatus()).isEqualTo(status);
        });
    }

    static Stream<RefundStepStatus> 결과_대기가_아닌_상태() {
        return Stream.of(RefundStepStatus.READY, RefundStepStatus.IN_PROGRESS,
                RefundStepStatus.SUCCEEDED, RefundStepStatus.REFUSED, RefundStepStatus.STOPPED);
    }

    static Stream<RefundStepStatus> 시도_중이_아닌_상태() {
        return Stream.of(RefundStepStatus.READY, RefundStepStatus.AWAITING_RESULT,
                RefundStepStatus.SUCCEEDED, RefundStepStatus.REFUSED, RefundStepStatus.STOPPED);
    }

    @ParameterizedTest
    @MethodSource("비어_있는_이유")
    void 이유가_비면_거부한다(String reason) {
        // given
        RefundStep sut = RefundStep.start(10L, RefundStepType.ORDER, NOW);

        // when & then
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
