package com.ok.payout.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 대사 줄 하나의 판정 규칙 (#619). 예시는 모두 정산 15번, 판매자 5번, 9,700원
class PayoutDepositCheckTest {

    private static final Long PAYOUT_ID = 15L;
    private static final Long PAYEE_ID = 5L;
    private static final Long AMOUNT = 9_700L;
    private static final LocalDateTime ASKED_AT = LocalDateTime.of(2026, 11, 15, 12, 0);
    private static final LocalDateTime DEPOSITED_AT = LocalDateTime.of(2026, 11, 15, 2, 5);

    @Test
    void 같은_판매자_금액으로_있음이면_일치로_끝난다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.MATCHED);
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED);
            softly.assertThat(sut.getMismatchReason()).isNull();
            softly.assertThat(sut.getWalletDepositedAt()).isEqualTo(DEPOSITED_AT);
        });
    }

    @Test
    void 판매자가_다르면_금액이_달라도_판매자_다름으로_바로_관리자_확인이다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, 6L, 9_000L, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.NEEDS_REVIEW);
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.NEEDS_REVIEW);
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.PAYEE_MISMATCH);
            softly.assertThat(sut.getWalletPayeeId()).isEqualTo(6L);
            softly.assertThat(sut.getWalletAmount()).isEqualTo(9_000L);
        });
    }

    @Test
    void 같은_판매자인데_금액만_다르면_금액_다름으로_바로_관리자_확인이다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, 9_000L, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.NEEDS_REVIEW);
        assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.AMOUNT_MISMATCH);
    }

    @Test
    void 없음이고_물을_횟수가_남으면_대기로_남고_다시_보내라고_알린다() {
        // given: 1번째 질문
        PayoutDepositCheck sut = askedOnce(3L);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, false, null, null, null);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.NOT_FOUND_RETRY);
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);
            softly.assertThat(sut.getNotFoundCount()).isEqualTo(1);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
        });
    }

    @Test
    void 세_번째_질문에도_없음이면_입금_없음으로_관리자_확인이다() {
        // given: 1일차 없음, 2일차 답 안 옴, 3일차 질문
        PayoutDepositCheck sut = askedOnce(1L);
        sut.applyAnswer(1L, false, null, null, null);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));
        sut.recordAsked(3L, ASKED_AT.plusDays(2));

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, false, null, null, null);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.NEEDS_REVIEW);
        assertSoftly(softly -> {
            softly.assertThat(sut.getAttemptCount()).isEqualTo(3);
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.NOT_FOUND);
        });
    }

    @Test
    void 가장_최근_질문의_답이_아니면_버린다() {
        // given: 어제 질문 3, 오늘 질문 7
        PayoutDepositCheck sut = askedOnce(3L);
        sut.recordAsked(7L, ASKED_AT.plusDays(1));

        // when: 질문 3의 "없음"이 늦게 도착
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, false, null, null, null);

        // then: 버려지고 질문 7의 답은 여전히 받을 수 있음
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertThat(sut.getNotFoundCount()).isZero();
        assertThat(sut.applyAnswer(7L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT)).isEqualTo(PayoutDepositAnswerOutcome.MATCHED);
    }

    @Test
    void 같은_답이_두_번_오면_두_번째는_버린다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);
        sut.applyAnswer(3L, false, null, null, null);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, false, null, null, null);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertThat(sut.getNotFoundCount()).isEqualTo(1);
    }

    @Test
    void 끝난_줄에_온_답은_버린다() {
        // given: 이미 일치로 끝난 줄
        PayoutDepositCheck sut = askedOnce(3L);
        sut.applyAnswer(3L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, false, null, null, null);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED);
    }

    @Test
    void 세_번_물었는데_답이_한_번도_없으면_지갑_답_없음으로_닫는다() {
        // given
        PayoutDepositCheck sut = askedOnce(1L);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));
        sut.recordAsked(3L, ASKED_AT.plusDays(2));

        // when
        boolean closed = sut.closeIfNoAttemptsLeft();

        // then
        assertThat(closed).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.NEEDS_REVIEW);
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.NO_RESPONSE);
        });
    }

    @Test
    void 세_번_물었고_없음이_한_번이라도_왔으면_입금_없음으로_닫는다() {
        // given: 1일차 없음, 2·3일차 답 안 옴
        PayoutDepositCheck sut = askedOnce(1L);
        sut.applyAnswer(1L, false, null, null, null);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));
        sut.recordAsked(3L, ASKED_AT.plusDays(2));

        // when
        boolean closed = sut.closeIfNoAttemptsLeft();

        // then
        assertThat(closed).isTrue();
        assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.NOT_FOUND);
    }

    @Test
    void 물을_횟수가_남은_대기_줄은_닫지_않는다() {
        // given
        PayoutDepositCheck sut = askedOnce(1L);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));

        // when
        boolean closed = sut.closeIfNoAttemptsLeft();

        // then
        assertThat(closed).isFalse();
        assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);
    }

    @Test
    void 보내지_못해_되돌리면_물은_횟수에서_빠지고_그_질문의_답은_버린다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);

        // when
        boolean undone = sut.undoAsked(3L);

        // then
        assertThat(undone).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(sut.getAttemptCount()).isZero();
            softly.assertThat(sut.getLastCheckId()).isNull();
        });
        assertThat(sut.applyAnswer(3L, false, null, null, null)).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
    }

    @Test
    void 이미_답을_반영한_질문은_되돌리지_않는다() {
        // given: 보내자마자 지갑이 답해 이미 반영됨
        PayoutDepositCheck sut = askedOnce(3L);
        sut.applyAnswer(3L, false, null, null, null);

        // when
        boolean undone = sut.undoAsked(3L);

        // then
        assertThat(undone).isFalse();
        assertThat(sut.getAttemptCount()).isEqualTo(1);
    }

    private static PayoutDepositCheck askedOnce(Long checkId) {
        PayoutDepositCheck check = PayoutDepositCheck.waiting(PAYOUT_ID, PAYEE_ID, AMOUNT);
        check.recordAsked(checkId, ASKED_AT);
        return check;
    }
}
