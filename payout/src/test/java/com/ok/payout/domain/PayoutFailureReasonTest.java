package com.ok.payout.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 실패 사유 enum의 고정값 확인 (DB 칸 없이 enum으로 계산하는 값이라, 값이 바뀌면 과거 기록의 동작도 함께 바뀜)
class PayoutFailureReasonTest {

    @Test
    void 주문을_잠그는_사유는_초과_청구_수수료_결과_검증_불일치뿐이다() {
        // when
        List<PayoutFailureReason> sut = PayoutFailureReason.orderLockingReasons();

        // then: #391 1단계는 잠금 동작을 바꾸지 않음. FEE_MISMATCH는 나누기 전(AMOUNT_MISMATCH)처럼 잠그고, 함께 보류된 ORDER_HELD는 잠그지 않음
        assertThat(sut).containsExactly(
                PayoutFailureReason.AMOUNT_MISMATCH,
                PayoutFailureReason.FEE_MISMATCH,
                PayoutFailureReason.VERIFY_MISMATCH);
    }
}
