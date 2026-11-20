package com.ok.refund.domain;

import com.ok.refund.domain.OrderRevertResultPolicy.Decision;
import com.ok.refund.domain.OrderRevertResultPolicy.SkipReason;
import com.ok.refund.domain.OrderRevertResultPolicy.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 예시: 환불 7번. 주문 1000번의 주문 상품 101번 2개를 취소했는데 지갑이 반환에 실패해 주문에 취소 기록 되돌리기를 보냈다(#622)
class OrderRevertResultPolicyTest {

    private static final Long REFUND_ID = 7L;
    private static final Long ORDER_ID = 1_000L;
    private static final Long ORDER_ITEM_ID = 101L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Test
    void 처리_중이고_되돌리기_결과를_기다리며_값이_같으면_받는다() {
        // when
        Verdict sut = OrderRevertResultPolicy.judge(refund(RefundStatus.PROCESSING), orderRevert(RefundStepStatus.AWAITING_RESULT),
                ORDER_ID, ORDER_ITEM_ID);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.ACCEPT, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("어긋난_값")
    void 주문이나_주문_상품이_다르면_불일치다(String 경우, Long orderId, Long orderItemId) {
        // when
        Verdict sut = OrderRevertResultPolicy.judge(refund(RefundStatus.PROCESSING), orderRevert(RefundStepStatus.AWAITING_RESULT),
                orderId, orderItemId);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.MISMATCH, null));
    }

    static Stream<Arguments> 어긋난_값() {
        return Stream.of(
                Arguments.of("주문이 다름", 1_001L, ORDER_ITEM_ID),
                Arguments.of("주문 상품이 다름", ORDER_ID, 102L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("넘기는_경우")
    void 받을_곳이_없으면_넘긴다(String 경우, Refund refund, RefundStep orderRevert, SkipReason expected) {
        // when
        Verdict sut = OrderRevertResultPolicy.judge(refund, orderRevert, ORDER_ID, ORDER_ITEM_ID);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, expected));
    }

    static Stream<Arguments> 넘기는_경우() {
        return Stream.of(
                Arguments.of("환불 없음", null, null, SkipReason.REFUND_NOT_FOUND),
                Arguments.of("이미 실패로 끝남(같은 되돌림이 다시 옴)", refund(RefundStatus.FAILED),
                        orderRevert(RefundStepStatus.SUCCEEDED), SkipReason.NOT_PROCESSING),
                Arguments.of("되돌리기 줄 없음", refund(RefundStatus.PROCESSING), null, SkipReason.NO_REVERT_STEP),
                Arguments.of("되돌리기 줄 이미 멈춤", refund(RefundStatus.PROCESSING), orderRevert(RefundStepStatus.STOPPED),
                        SkipReason.ALREADY_FINISHED));
    }

    @Test
    void 끝난_되돌리기_줄은_값이_달라도_불일치가_아니라_넘긴다() {
        // when
        Verdict sut = OrderRevertResultPolicy.judge(refund(RefundStatus.PROCESSING), orderRevert(RefundStepStatus.STOPPED),
                ORDER_ID, 999L);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, SkipReason.ALREADY_FINISHED));
    }

    private static Refund refund(RefundStatus status) {
        return Refund.restore(REFUND_ID, ORDER_ID, ORDER_ITEM_ID, 2, 2_000L, 5L, 55L, "감자", "1kg", 10_000L, 20_000L,
                0L, 0L, 0L, null, status, null, null, null, null, null);
    }

    private static RefundStep orderRevert(RefundStepStatus status) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(5);
        return RefundStep.restore(5L, REFUND_ID, RefundStepType.ORDER_REVERT, status, 1, nextCheckAt, null, NOW);
    }
}
