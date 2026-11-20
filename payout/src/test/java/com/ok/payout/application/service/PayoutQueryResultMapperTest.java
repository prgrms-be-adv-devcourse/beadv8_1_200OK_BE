package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutCandidateResult;
import com.ok.payout.application.port.in.PayoutItemResult;
import com.ok.payout.application.port.out.CandidateRow;
import com.ok.payout.application.port.out.PayoutItemRow;
import com.ok.payout.domain.PayoutAdjustmentCheckStatus;
import com.ok.payout.domain.PayoutEventType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutQueryResultMapperTest {

    private static final LocalDateTime CANDIDATE_CREATED_AT = LocalDateTime.of(2026, 10, 14, 15, 20);

    @Test
    void 조회_결과_한_줄의_값을_항목_결과로_옮기고_항목_종류는_문자열로_바꾼다() {
        // given: ID들에 일부러 다른 숫자를 넣음. 같은 숫자면 서로 바뀌어 옮겨져도 티가 안 나서 실수를 못 잡음
        PayoutItemRow row = new PayoutItemRow(101L, 55L, PayoutEventType.SALE_FEE, 300L, 900L, 7L, 9_700L,
                CANDIDATE_CREATED_AT, 0L, null, null);

        // when
        PayoutItemResult sut = PayoutQueryResultMapper.toItemResult(row);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.payoutItemId()).isEqualTo(101L);
            softly.assertThat(sut.candidateId()).isEqualTo(55L);
            softly.assertThat(sut.eventType()).isEqualTo("SALE_FEE");
            softly.assertThat(sut.orderId()).isEqualTo(300L);
            softly.assertThat(sut.orderItemId()).isEqualTo(900L);
            softly.assertThat(sut.sellerId()).isEqualTo(7L);
            softly.assertThat(sut.amount()).isEqualTo(9_700L);
            softly.assertThat(sut.candidateCreatedAt()).isEqualTo(CANDIDATE_CREATED_AT);
        });
    }

    @Test
    void 평소_건이면_조정_정보가_없다() {
        // given: 조정 번호 0 = 평소 건
        PayoutItemRow row = itemRow(0L, null, null);

        // when
        PayoutItemResult sut = PayoutQueryResultMapper.toItemResult(row);

        // then
        assertThat(sut.adjustment()).isNull();
    }

    @Test
    void 조정_건이면_조정_번호_관리자_ID_사유를_모두_담는다() {
        // given
        PayoutItemRow row = itemRow(4L, 1L, "금액 오입력 정정");

        // when
        PayoutItemResult sut = PayoutQueryResultMapper.toItemResult(row);

        // then
        assertThat(sut.adjustment()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.adjustment().adjustmentId()).isEqualTo(4L);
            softly.assertThat(sut.adjustment().adminId()).isEqualTo(1L);
            softly.assertThat(sut.adjustment().reason()).isEqualTo("금액 오입력 정정");
        });
    }

    @Test
    void 조회_결과_한_줄의_값을_후보_결과로_옮기고_항목_종류는_문자열로_바꾼다() {
        // given: ID들에 일부러 다른 숫자를 넣음. 같은 숫자면 서로 바뀌어 옮겨져도 티가 안 나서 실수를 못 잡음
        CandidateRow row = new CandidateRow(55L, PayoutEventType.SALE_SHIPPING_FEE, 300L, 900L, 7L, 9_700L,
                CANDIDATE_CREATED_AT, 0L, null, null, 12L);

        // when
        PayoutCandidateResult sut = PayoutQueryResultMapper.toCandidateResult(row);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.candidateId()).isEqualTo(55L);
            softly.assertThat(sut.eventType()).isEqualTo("SALE_SHIPPING_FEE");
            softly.assertThat(sut.orderId()).isEqualTo(300L);
            softly.assertThat(sut.orderItemId()).isEqualTo(900L);
            softly.assertThat(sut.sellerId()).isEqualTo(7L);
            softly.assertThat(sut.amount()).isEqualTo(9_700L);
            softly.assertThat(sut.candidateCreatedAt()).isEqualTo(CANDIDATE_CREATED_AT);
            softly.assertThat(sut.payoutId()).isEqualTo(12L);
        });
    }

    @Test
    void 정산_ID가_있으면_정산된_후보다() {
        // given
        CandidateRow row = candidateRow(PayoutEventType.SALE_AMOUNT, 12L);

        // when
        PayoutCandidateResult sut = PayoutQueryResultMapper.toCandidateResult(row);

        // then
        assertThat(sut.settled()).isTrue();
    }

    @Test
    void 정산_ID가_없으면_정산_전_후보다() {
        // given: payout_item과 조인되지 않은 후보
        CandidateRow row = candidateRow(PayoutEventType.SALE_AMOUNT, null);

        // when
        PayoutCandidateResult sut = PayoutQueryResultMapper.toCandidateResult(row);

        // then
        assertThat(sut.settled()).isFalse();
    }

    @Test
    void 후보_결과도_평소_건이면_조정_정보가_없다() {
        // given: 조정 번호 0 = 평소 건
        CandidateRow row = candidateRow(PayoutEventType.SALE_AMOUNT, 12L);

        // when
        PayoutCandidateResult sut = PayoutQueryResultMapper.toCandidateResult(row);

        // then
        assertThat(sut.adjustment()).isNull();
    }

    @Test
    void 후보_결과도_조정_건이면_조정_번호_관리자_ID_사유를_모두_담는다() {
        // given
        CandidateRow row = new CandidateRow(55L, PayoutEventType.SALE_AMOUNT, 300L, 900L, 7L, -2_000L,
                CANDIDATE_CREATED_AT, 4L, 1L, "금액 오입력 정정", 12L);

        // when
        PayoutCandidateResult sut = PayoutQueryResultMapper.toCandidateResult(row);

        // then
        assertThat(sut.adjustment()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.adjustment().adjustmentId()).isEqualTo(4L);
            softly.assertThat(sut.adjustment().adminId()).isEqualTo(1L);
            softly.assertThat(sut.adjustment().reason()).isEqualTo("금액 오입력 정정");
        });
    }

    // 조정 상태 (#791): 두 ID 목록에 없으면 검사 밖, 둘 다 있으면 확인됨을 먼저 봄
    @Test
    void 조정_상태는_확인됨을_먼저_보고_두_목록에_없으면_검사_밖이다() {
        // given
        Set<Long> confirmedIds = Set.of(1L, 3L);
        Set<Long> awaitingIds = Set.of(2L, 3L);

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(PayoutQueryResultMapper.adjustmentCheckStatus(1L, confirmedIds, awaitingIds))
                    .isEqualTo(PayoutAdjustmentCheckStatus.CONFIRMED);
            softly.assertThat(PayoutQueryResultMapper.adjustmentCheckStatus(2L, confirmedIds, awaitingIds))
                    .isEqualTo(PayoutAdjustmentCheckStatus.AWAITING_RESOLUTION);
            softly.assertThat(PayoutQueryResultMapper.adjustmentCheckStatus(3L, confirmedIds, awaitingIds))
                    .isEqualTo(PayoutAdjustmentCheckStatus.CONFIRMED);
            softly.assertThat(PayoutQueryResultMapper.adjustmentCheckStatus(4L, confirmedIds, awaitingIds))
                    .isEqualTo(PayoutAdjustmentCheckStatus.UNCHECKED);
        });
    }

    @Test
    void 조정_상태는_조정_건에만_이름으로_담고_평소_건은_null이다() {
        // given
        CandidateRow adjustmentRow = new CandidateRow(55L, PayoutEventType.SALE_AMOUNT, 300L, 900L, 7L, -2_000L,
                CANDIDATE_CREATED_AT, 4L, 1L, "금액 오입력 정정", null);
        CandidateRow originalRow = new CandidateRow(56L, PayoutEventType.SALE_AMOUNT, 300L, 900L, 7L, 9_700L,
                CANDIDATE_CREATED_AT, 0L, null, null, null);

        // when
        PayoutCandidateResult adjustment = PayoutQueryResultMapper.toCandidateResult(adjustmentRow,
                PayoutAdjustmentCheckStatus.AWAITING_RESOLUTION);
        PayoutCandidateResult original = PayoutQueryResultMapper.toCandidateResult(originalRow,
                PayoutAdjustmentCheckStatus.UNCHECKED);

        // then
        assertSoftly(softly -> {
            softly.assertThat(adjustment.adjustmentCheckStatus()).isEqualTo("AWAITING_RESOLUTION");
            softly.assertThat(original.adjustmentCheckStatus()).isNull();
        });
    }

    private PayoutItemRow itemRow(Long adjustmentId, Long adminId, String reason) {
        return new PayoutItemRow(101L, 55L, PayoutEventType.SALE_AMOUNT, 300L, 900L, 7L, -2_000L,
                CANDIDATE_CREATED_AT, adjustmentId, adminId, reason);
    }

    // 항목 종류와 정산 ID만 바꾼 조회 결과 (평소 건)
    private CandidateRow candidateRow(PayoutEventType eventType, Long payoutId) {
        return new CandidateRow(55L, eventType, 300L, 900L, 7L, 9_700L, CANDIDATE_CREATED_AT, 0L, null, null, payoutId);
    }
}
