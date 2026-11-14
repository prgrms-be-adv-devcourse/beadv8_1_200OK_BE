package com.ok.payout.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutBlockedOrderTest {

    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 11, 14, 10, 0);

    @Test
    void 예비_줄은_초과_청구_근거_없이_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> PayoutBlockedOrder.pending(1L, FAILED_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("예비 줄에는 주문 ID, 실패 시각, 초과 청구 근거가 필요합니다.");
    }

    @Test
    void 다시_판정한_결과로_예비_상태로_바꿀_수_없다() {
        // given
        PayoutBlockedOrder blockedOrder = PayoutBlockedOrder.pending(1L, FAILED_AT, evidence());

        // when & then
        assertThatThrownBy(() -> blockedOrder.change(PayoutBlockedStatus.PENDING, 0, FAILED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("다시 판정한 막힌 주문은 LOCKED·STOPPED 상태와 마지막 실패 시각이 필요합니다.");
    }

    @Test
    void 근거_없이_근거를_바꿀_수_없다() {
        // given
        PayoutBlockedOrder blockedOrder = PayoutBlockedOrder.pending(1L, FAILED_AT, evidence());

        // when & then
        assertThatThrownBy(() -> blockedOrder.recordEvidence(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("초과 청구 근거가 필요합니다.");
    }

    private PayoutOverchargeEvidence evidence() {
        return PayoutOverchargeEvidence.of(30_000L, 35_000L, "1", 0L, List.of(), List.of("1"), 1, List.of());
    }
}
