package com.ok.refund.domain;

import com.ok.refund.domain.RefundSagaPlan.Action;
import com.ok.refund.domain.RefundSagaPlan.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 예시: 환불 7번. 돌려줄 돈이 있으면 20,000원(지갑을 거침), 0원이면 지갑 없이 재고로 간다
class RefundSagaPlanTest {

    private static final Long REFUND_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);

    // ---- 방금 받은 답으로 정하기 ----

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("답별_다음_할_일")
    void 방금_받은_답으로_다음_할_일을_정한다(Event event, long refundAmount, Action expected) {
        // when
        Action sut = RefundSagaPlan.afterEvent(processing(refundAmount), event);

        // then
        assertThat(sut).isEqualTo(expected);
    }

    static Stream<Arguments> 답별_다음_할_일() {
        return Stream.of(
                Arguments.of(Event.ORDER_REFLECTED, 20_000L, Action.CALL_WALLET),
                Arguments.of(Event.ORDER_REFLECTED, 0L, Action.CALL_PRODUCT),
                Arguments.of(Event.ORDER_REFUSED, 20_000L, Action.REJECT),
                Arguments.of(Event.WALLET_PUBLISHED, 20_000L, Action.WAIT),
                Arguments.of(Event.WALLET_COMPLETED, 20_000L, Action.CALL_PRODUCT),
                Arguments.of(Event.PRODUCT_RELEASED, 20_000L, Action.COMPLETE),
                Arguments.of(Event.PRODUCT_RELEASED, 0L, Action.COMPLETE),
                // 재고가 다시 해도 거절되면 돌려준 돈이 있을 때만 회수한다(#546). 0원은 가져올 돈이 없어 멈춘다
                Arguments.of(Event.PRODUCT_REFUSED, 20_000L, Action.CALL_WALLET_REVERT),
                Arguments.of(Event.PRODUCT_REFUSED, 0L, Action.STOP),
                Arguments.of(Event.WALLET_REVERT_PUBLISHED, 20_000L, Action.WAIT));
    }

    @ParameterizedTest
    @MethodSource("처리_중이_아닌_상태")
    void 처리_중이_아닌_환불은_답과_상관없이_멈춘다(RefundStatus status) {
        // when
        Action sut = RefundSagaPlan.afterEvent(refund(status, 20_000L), Event.ORDER_REFLECTED);

        // then
        assertThat(sut).isEqualTo(Action.STOP);
    }

    static Stream<RefundStatus> 처리_중이_아닌_상태() {
        return Stream.of(RefundStatus.REQUESTED, RefundStatus.REJECTED, RefundStatus.WITHDRAWN, RefundStatus.COMPLETED);
    }

    // ---- 단계 줄만 보고 정하기 (알람이 깨운 경우) ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("단계_줄별_다음_할_일")
    void 단계_줄만_보고_다음_할_일을_정한다(String 경우, long refundAmount, List<RefundStep> steps, Action expected) {
        // when
        Action sut = RefundSagaPlan.fromSteps(processing(refundAmount), steps, NOW);

        // then
        assertThat(sut).isEqualTo(expected);
    }

    static Stream<Arguments> 단계_줄별_다음_할_일() {
        return Stream.of(
                Arguments.of("주문 줄 없음(옛 환불) → 멈춤", 20_000L, List.of(), Action.STOP),
                Arguments.of("옛 재고 멈춤 줄만 있음 → 멈춤", 20_000L, List.of(step(RefundStepType.PRODUCT, RefundStepStatus.STOPPED)), Action.STOP),
                Arguments.of("주문 할 차례 → 주문", 20_000L, List.of(step(RefundStepType.ORDER, RefundStepStatus.READY)), Action.CALL_ORDER),
                Arguments.of("주문 시도 중 → 기다림", 20_000L, List.of(step(RefundStepType.ORDER, RefundStepStatus.IN_PROGRESS)), Action.WAIT),
                Arguments.of("주문 시도 중인데 만료 지남(#576) → 주문", 20_000L, List.of(expired(RefundStepType.ORDER)), Action.CALL_ORDER),
                Arguments.of("주문 성공, 지갑 시도 중인데 만료 지남 → 지갑", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), expired(RefundStepType.WALLET)), Action.CALL_WALLET),
                Arguments.of("지갑 성공, 재고 시도 중인데 만료 지남 → 재고", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), succeeded(RefundStepType.WALLET), expired(RefundStepType.PRODUCT)), Action.CALL_PRODUCT),
                Arguments.of("주문 거절됨인데 처리 중 → 멈춤", 20_000L, List.of(step(RefundStepType.ORDER, RefundStepStatus.REFUSED)), Action.STOP),
                Arguments.of("주문 성공인데 지갑 줄 없음(같은 구간에서 만들어 있을 수 없음) → 멈춤", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER)), Action.STOP),
                Arguments.of("주문 성공, 지갑 할 차례 → 지갑", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), step(RefundStepType.WALLET, RefundStepStatus.READY)), Action.CALL_WALLET),
                Arguments.of("지갑 결과 대기 → 기다림", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), step(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT)), Action.WAIT),
                Arguments.of("지갑 결과 대기인데 기다리는 시각 지남(#577) → 지갑 다시 요청", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), overdueAwaiting()), Action.CALL_WALLET),
                Arguments.of("지갑 멈춤 → 멈춤", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), step(RefundStepType.WALLET, RefundStepStatus.STOPPED)), Action.STOP),
                Arguments.of("지갑 성공인데 재고 줄 없음(같은 구간에서 만들어 있을 수 없음) → 멈춤", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), succeeded(RefundStepType.WALLET)), Action.STOP),
                Arguments.of("불일치로 지갑 멈춤 뒤 맞는 완료로 재고 할 차례(#544 결정 8) → 재고", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), step(RefundStepType.WALLET, RefundStepStatus.STOPPED),
                                step(RefundStepType.PRODUCT, RefundStepStatus.READY)), Action.CALL_PRODUCT),
                Arguments.of("불일치로 지갑 멈춤 뒤 재고 시도 중 → 기다림", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), step(RefundStepType.WALLET, RefundStepStatus.STOPPED),
                                step(RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS)), Action.WAIT),
                Arguments.of("재고 할 차례 → 재고", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), succeeded(RefundStepType.WALLET), step(RefundStepType.PRODUCT, RefundStepStatus.READY)),
                        Action.CALL_PRODUCT),
                Arguments.of("재고 성공 → 완료", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), succeeded(RefundStepType.WALLET), succeeded(RefundStepType.PRODUCT)), Action.COMPLETE),
                Arguments.of("0원, 주문 성공인데 재고 줄 없음 → 멈춤", 0L, List.of(succeeded(RefundStepType.ORDER)), Action.STOP),
                Arguments.of("0원, 재고 할 차례 → 지갑 없이 재고", 0L,
                        List.of(succeeded(RefundStepType.ORDER), step(RefundStepType.PRODUCT, RefundStepStatus.READY)), Action.CALL_PRODUCT),
                Arguments.of("0원, 재고 시도 중 → 기다림", 0L,
                        List.of(succeeded(RefundStepType.ORDER), step(RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS)), Action.WAIT),
                // 회수(#546): 회수 줄이 있으면 다른 줄보다 먼저 본다
                Arguments.of("재고 멈춤, 회수 할 차례 → 회수", 20_000L, refusedWith(step(RefundStepType.WALLET_REVERT, RefundStepStatus.READY)),
                        Action.CALL_WALLET_REVERT),
                Arguments.of("회수 결과 대기 → 기다림", 20_000L,
                        refusedWith(step(RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT)), Action.WAIT),
                Arguments.of("회수 결과 대기인데 기다리는 시각 지남(#577) → 회수 다시 요청", 20_000L,
                        refusedWith(overdueAwaiting(RefundStepType.WALLET_REVERT)), Action.CALL_WALLET_REVERT),
                Arguments.of("회수 시도 중인데 만료 지남 → 회수", 20_000L, refusedWith(expired(RefundStepType.WALLET_REVERT)),
                        Action.CALL_WALLET_REVERT),
                Arguments.of("회수 성공(주문 되돌리기 #622 전) → 멈춤", 20_000L, refusedWith(succeeded(RefundStepType.WALLET_REVERT)),
                        Action.STOP),
                Arguments.of("회수 실패로 멈춤 → 멈춤", 20_000L, refusedWith(step(RefundStepType.WALLET_REVERT, RefundStepStatus.STOPPED)),
                        Action.STOP),
                Arguments.of("회수 결과 대기 중이면 재고 멈춤이 아니라 회수 줄로 정함(지갑 결과 대기 시각이 지나도) → 기다림", 20_000L,
                        List.of(succeeded(RefundStepType.ORDER), overdueAwaiting(RefundStepType.WALLET),
                                step(RefundStepType.PRODUCT, RefundStepStatus.STOPPED),
                                step(RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT)), Action.WAIT));
    }

    // 지갑 반환까지 끝났는데 재고가 거절로 멈춘 환불에 회수 줄 하나를 더한 단계 줄
    private static List<RefundStep> refusedWith(RefundStep revert) {
        return List.of(succeeded(RefundStepType.ORDER), succeeded(RefundStepType.WALLET),
                step(RefundStepType.PRODUCT, RefundStepStatus.STOPPED), revert);
    }

    @Test
    void 단계_줄로_정할_때도_처리_중이_아닌_환불은_멈춘다() {
        // when
        Action sut = RefundSagaPlan.fromSteps(refund(RefundStatus.COMPLETED, 20_000L), List.of(succeeded(RefundStepType.ORDER)), NOW);

        // then
        assertThat(sut).isEqualTo(Action.STOP);
    }

    // 돌려줄 금액 = 상품 금액(itemAmount). 배송비 칸은 0으로 둔다
    private static Refund processing(long refundAmount) {
        return refund(RefundStatus.PROCESSING, refundAmount);
    }

    private static Refund refund(RefundStatus status, long refundAmount) {
        return Refund.restore(REFUND_ID, 1_000L, 101L, 2, 2_000L, 5L, 55L, "감자", "1kg", 10_000L, refundAmount,
                0L, 0L, 0L, null, status, null, null, null, null, null);
    }

    private static RefundStep step(RefundStepType type, RefundStepStatus status) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(1);
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type, status, 1, nextCheckAt, null, NOW);
    }

    // 시도 중인데 만료 시각이 지금인 줄(시도하던 쪽이 멈춤)
    private static RefundStep expired(RefundStepType type) {
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type, RefundStepStatus.IN_PROGRESS, 1, NOW, null, NOW.minusMinutes(2));
    }

    // 지갑 결과 대기인데 기다리는 시각이 지금인 줄(결과 메시지가 늦거나 버려짐)
    private static RefundStep overdueAwaiting() {
        return overdueAwaiting(RefundStepType.WALLET);
    }

    private static RefundStep overdueAwaiting(RefundStepType type) {
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type,
                RefundStepStatus.AWAITING_RESULT, 1, NOW, null, NOW.minusMinutes(5));
    }

    private static RefundStep succeeded(RefundStepType type) {
        return step(type, RefundStepStatus.SUCCEEDED);
    }
}
