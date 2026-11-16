package com.ok.payout.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

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
    void 보낸_줄을_다시_보낼_것으로_되돌리면_보낸_시각은_비우고_실패_시도_수는_그대로_둔다() {
        // given: 한 번 실패한 뒤 보낸 줄 (#620 대사 "입금 없음")
        PayoutOutbox sut = PayoutOutbox.pending(1L);
        sut.recordFailure("TimeoutException: 브로커 응답 없음");
        sut.markSent(LocalDateTime.of(2026, 11, 15, 2, 5));

        // when
        sut.markPendingAgain();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);
            softly.assertThat(sut.getSentAt()).isNull();
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
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
