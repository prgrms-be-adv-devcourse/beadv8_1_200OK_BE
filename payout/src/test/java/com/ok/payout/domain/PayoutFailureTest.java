package com.ok.payout.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutFailureTest {

    @Test
    void 상세가_255자_이하면_그대로_둔다() {
        // given: 정확히 255자 (경계)
        String detail = "가".repeat(PayoutFailure.MAX_DETAIL_LENGTH);

        // when
        String sut = PayoutFailure.fitDetail(detail);

        // then
        assertThat(sut).isEqualTo(detail);
    }

    @Test
    void 상세가_255자를_넘으면_255자로_자르고_끝에_말줄임표를_붙인다() {
        // given: 256자
        String detail = "가".repeat(PayoutFailure.MAX_DETAIL_LENGTH + 1);

        // when
        String sut = PayoutFailure.fitDetail(detail);

        // then
        assertThat(sut).hasSize(PayoutFailure.MAX_DETAIL_LENGTH).endsWith("...");
    }
}
