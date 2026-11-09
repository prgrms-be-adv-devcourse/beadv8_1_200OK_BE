package com.ok.payout.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutOutboxTest {

    @Test
    void 실패를_기록하면_시도_수가_1_늘고_500자_이하_오류는_그대로_남는다() {
        // given
        PayoutOutbox sut = PayoutOutbox.pending(1L);

        // when
        sut.recordFailure("TimeoutException: 브로커 응답 없음");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
            softly.assertThat(sut.getLastError()).isEqualTo("TimeoutException: 브로커 응답 없음");
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);
        });
    }

    @Test
    void 오류가_null이어도_시도_수는_늘고_오류는_비운다() {
        // given
        PayoutOutbox sut = PayoutOutbox.pending(1L);
        sut.recordFailure("이전 오류");

        // when
        sut.recordFailure(null);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getAttemptCount()).isEqualTo(2);
            softly.assertThat(sut.getLastError()).isNull();
        });
    }
}
