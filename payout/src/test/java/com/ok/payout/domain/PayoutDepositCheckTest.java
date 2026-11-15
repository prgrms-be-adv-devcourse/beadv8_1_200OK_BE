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
    void 이전_질문의_답이라도_있음이고_같은_판매자_금액이면_일치로_끝낸다() {
        // given: 어제 질문 3, 오늘 질문 7
        PayoutDepositCheck sut = askedOnce(3L);
        sut.recordAsked(7L, ASKED_AT.plusDays(1));

        // when: 질문 3의 "있음, 같은 값"이 늦게 도착 (지갑 원장 입금 줄은 지워지지 않아 지금도 사실)
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.MATCHED);
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED);
            softly.assertThat(sut.getWalletDepositedAt()).isEqualTo(DEPOSITED_AT);
        });
    }

    @Test
    void 이전_질문의_늦은_있음이_값이_다르면_바로_관리자_확인이다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);
        sut.recordAsked(7L, ASKED_AT.plusDays(1));

        // when: 질문 3의 "있음, 금액 다름"이 늦게 도착 (원장은 바뀌지 않아 지금도 사실)
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, 9_000L, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.NEEDS_REVIEW);
        assertSoftly(softly -> {
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.AMOUNT_MISMATCH);
            softly.assertThat(sut.getWalletAmount()).isEqualTo(9_000L);
        });
    }

    @Test
    void 최근_질문의_없음을_반영한_뒤에_온_이전_질문의_있음도_판정한다() {
        // given: 질문 3에 답이 없었고, 질문 7의 "없음"을 이미 반영함
        PayoutDepositCheck sut = askedOnce(3L);
        sut.recordAsked(7L, ASKED_AT.plusDays(1));
        sut.applyAnswer(7L, false, null, null, null);

        // when: 질문 3의 "있음, 같은 값"이 늦게 도착
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.MATCHED);
    }

    @Test
    void 지갑_답_없음으로_닫힌_줄에_늦은_있음이_같은_값이면_일치로_바꾼다() {
        // given: 세 번 물었는데 답이 없어 지갑 답 없음으로 닫힘
        PayoutDepositCheck sut = askedOnce(1L);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));
        sut.recordAsked(3L, ASKED_AT.plusDays(2));
        sut.closeIfNoAttemptsLeft();

        // when: 질문 2의 "있음, 같은 값"이 늦게 도착
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(2L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.REVIEW_CLEARED);
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED);
            softly.assertThat(sut.getMismatchReason()).isNull();
            softly.assertThat(sut.getWalletDepositedAt()).isEqualTo(DEPOSITED_AT);
        });
    }

    @Test
    void 지갑_답_없음으로_닫힌_줄에_늦은_있음이_값이_다르면_이유를_바꾸고_지갑_값을_남긴다() {
        // given
        PayoutDepositCheck sut = askedOnce(1L);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));
        sut.recordAsked(3L, ASKED_AT.plusDays(2));
        sut.closeIfNoAttemptsLeft();

        // when: 질문 2의 "있음, 판매자 다름"이 늦게 도착
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(2L, true, 6L, AMOUNT, DEPOSITED_AT);

        // then: 관리자 확인 필요는 그대로, 이유와 증거가 바뀜
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.NEEDS_REVIEW);
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.NEEDS_REVIEW);
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.PAYEE_MISMATCH);
            softly.assertThat(sut.getWalletPayeeId()).isEqualTo(6L);
        });
    }

    @Test
    void 세_번째_답을_못_받고_입금_없음으로_닫힌_줄에_늦은_있음이_같은_값이면_일치로_바꾼다() {
        // given: 1번째 "없음", 2·3번째 답 없음 → 대사가 입금 없음으로 닫음
        PayoutDepositCheck sut = askedOnce(1L);
        sut.applyAnswer(1L, false, null, null, null);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));
        sut.recordAsked(3L, ASKED_AT.plusDays(2));
        sut.closeIfNoAttemptsLeft();
        assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.NOT_FOUND);

        // when: 질문 3의 "있음, 같은 값"이 늦게 도착
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.REVIEW_CLEARED);
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED);
            softly.assertThat(sut.getMismatchReason()).isNull();
        });
    }

    @Test
    void 지갑_답_없음으로_닫힌_줄에_늦은_있음이_금액만_다르면_금액_다름으로_바꾼다() {
        // given
        PayoutDepositCheck sut = closedWithoutAnyAnswer();

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(2L, true, PAYEE_ID, 9_000L, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.NEEDS_REVIEW);
        assertSoftly(softly -> {
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.AMOUNT_MISMATCH);
            softly.assertThat(sut.getWalletAmount()).isEqualTo(9_000L);
        });
    }

    @Test
    void 늦은_답으로_판매자_다름이_된_뒤에_또_온_늦은_있음은_버린다() {
        // given: 지갑 답 없음으로 닫힘 → 늦은 "있음, 판매자 다름"으로 이유가 바뀜
        PayoutDepositCheck sut = closedWithoutAnyAnswer();
        sut.applyAnswer(2L, true, 6L, AMOUNT, DEPOSITED_AT);

        // when: 다른 질문의 "있음, 같은 값"이 또 늦게 옴
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(1L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then: 판매자 다름 그대로
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertSoftly(softly -> {
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.PAYEE_MISMATCH);
            softly.assertThat(sut.getWalletPayeeId()).isEqualTo(6L);
        });
    }

    @Test
    void 답을_못_받고_닫힌_줄에_늦은_없음이_오면_버리고_이유는_그대로다() {
        // given
        PayoutDepositCheck sut = closedWithoutAnyAnswer();

        // when: 3번째 질문의 "없음"이 늦게 옴
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, false, null, null, null);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertSoftly(softly -> {
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.NO_RESPONSE);
            softly.assertThat(sut.getNotFoundCount()).isZero();
        });
    }

    @Test
    void 같은_있음_다름_답이_두_번_오면_두_번째는_버린다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);
        sut.applyAnswer(3L, true, PAYEE_ID, 9_000L, DEPOSITED_AT);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, 9_000L, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.AMOUNT_MISMATCH);
    }

    @Test
    void 늦은_답도_판매자_다름을_금액_다름보다_먼저_본다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);
        sut.recordAsked(7L, ASKED_AT.plusDays(1));

        // when: 질문 3의 "있음, 판매자·금액 모두 다름"이 늦게 도착
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, 6L, 9_000L, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.NEEDS_REVIEW);
        assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.PAYEE_MISMATCH);
    }

    @Test
    void 늦은_답으로_일치가_된_뒤에_온_답은_버린다() {
        // given: 지갑 답 없음으로 닫혔다가 늦은 "있음, 같은 값"으로 일치가 됨
        PayoutDepositCheck sut = askedOnce(1L);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));
        sut.recordAsked(3L, ASKED_AT.plusDays(2));
        sut.closeIfNoAttemptsLeft();
        sut.applyAnswer(2L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // when: 질문 1의 "있음, 금액 다름"이 더 늦게 도착
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(1L, true, PAYEE_ID, 9_000L, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED);
    }

    @Test
    void 판매자_다름으로_닫힌_줄은_늦은_있음이_같은_값이어도_건드리지_않는다() {
        // given
        PayoutDepositCheck sut = askedOnce(3L);
        sut.applyAnswer(3L, true, 6L, AMOUNT, DEPOSITED_AT);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.PAYEE_MISMATCH);
    }

    @Test
    void 세_번째_질문에_없음으로_답해_닫힌_줄은_늦은_있음이_와도_건드리지_않는다() {
        // given: 세 번째 질문에도 "없음"이라 입금 없음으로 닫힘
        PayoutDepositCheck sut = askedOnce(1L);
        sut.recordAsked(2L, ASKED_AT.plusDays(1));
        sut.recordAsked(3L, ASKED_AT.plusDays(2));
        sut.applyAnswer(3L, false, null, null, null);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(2L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.IGNORED);
        assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.NOT_FOUND);
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
    void 보내지_못해_되돌리면_물은_횟수에서_빠지고_그_질문의_없음_답은_버린다() {
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
    void 보내지_못한_줄_알고_되돌린_질문에_있음_답이_오면_판정한다() {
        // given: 브로커가 시간 초과를 냈지만 실제로는 질문이 저장돼 지갑이 답함
        PayoutDepositCheck sut = askedOnce(3L);
        sut.undoAsked(3L);

        // when
        PayoutDepositAnswerOutcome outcome = sut.applyAnswer(3L, true, PAYEE_ID, AMOUNT, DEPOSITED_AT);

        // then
        assertThat(outcome).isEqualTo(PayoutDepositAnswerOutcome.MATCHED);
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

    // 질문 1·2·3에 답이 한 번도 없어 대사가 "지갑 답 없음"으로 닫은 줄
    private static PayoutDepositCheck closedWithoutAnyAnswer() {
        PayoutDepositCheck check = askedOnce(1L);
        check.recordAsked(2L, ASKED_AT.plusDays(1));
        check.recordAsked(3L, ASKED_AT.plusDays(2));
        check.closeIfNoAttemptsLeft();
        return check;
    }

    private static PayoutDepositCheck askedOnce(Long checkId) {
        PayoutDepositCheck check = PayoutDepositCheck.waiting(PAYOUT_ID, PAYEE_ID, AMOUNT);
        check.recordAsked(checkId, ASKED_AT);
        return check;
    }
}
