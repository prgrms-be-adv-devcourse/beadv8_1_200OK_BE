package com.ok.payout.service.dto;

import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.repository.PayoutQueryRepository.PayoutItemRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutItemResultTest {

    private static final LocalDateTime CANDIDATE_CREATED_AT = LocalDateTime.of(2026, 10, 14, 15, 20);

    @Test
    void 조회_결과_한_줄의_값을_항목_결과로_옮기고_항목_종류는_문자열로_바꾼다() {
        // given: ID들에 일부러 다른 숫자를 넣음. 같은 숫자면 서로 바뀌어 옮겨져도 티가 안 나서 실수를 못 잡음
        PayoutItemRow row = new PayoutItemRow(101L, 55L, PayoutEventType.SALE_AMOUNT, 300L, 900L, 7L, 9_700L,
                CANDIDATE_CREATED_AT, 0L, null, null);

        // when
        PayoutItemResult sut = PayoutItemResult.from(row, true);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.payoutItemId()).isEqualTo(101L);
            softly.assertThat(sut.candidateId()).isEqualTo(55L);
            softly.assertThat(sut.eventType()).isEqualTo("SALE_AMOUNT");
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
        PayoutItemRow row = row(0L, null, null);

        // when
        PayoutItemResult sut = PayoutItemResult.from(row, true);

        // then
        assertThat(sut.adjustment()).isNull();
    }

    @Test
    void 관리자가_조회하면_조정_번호_관리자_ID_사유를_모두_준다() {
        // given
        PayoutItemRow row = row(4L, 1L, "금액 오입력 정정");

        // when
        PayoutItemResult sut = PayoutItemResult.from(row, true);

        // then
        assertThat(sut.adjustment()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.adjustment().adjustmentId()).isEqualTo(4L);
            softly.assertThat(sut.adjustment().adminId()).isEqualTo(1L);
            softly.assertThat(sut.adjustment().reason()).isEqualTo("금액 오입력 정정");
        });
    }

    @Test
    void 판매자가_조회하면_조정_사유만_준다() {
        // given
        PayoutItemRow row = row(4L, 1L, "금액 오입력 정정");

        // when
        PayoutItemResult sut = PayoutItemResult.from(row, false);

        // then
        assertThat(sut.adjustment()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.adjustment().adjustmentId()).isNull();
            softly.assertThat(sut.adjustment().adminId()).isNull();
            softly.assertThat(sut.adjustment().reason()).isEqualTo("금액 오입력 정정");
        });
    }

    private PayoutItemRow row(Long adjustmentId, Long adminId, String reason) {
        return new PayoutItemRow(101L, 55L, PayoutEventType.SALE_AMOUNT, 300L, 900L, 7L, -2_000L,
                CANDIDATE_CREATED_AT, adjustmentId, adminId, reason);
    }
}
