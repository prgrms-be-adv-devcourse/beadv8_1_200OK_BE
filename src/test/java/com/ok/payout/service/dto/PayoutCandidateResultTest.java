package com.ok.payout.service.dto;

import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.repository.PayoutQueryRepository.CandidateRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutCandidateResultTest {

    private static final LocalDateTime CANDIDATE_CREATED_AT = LocalDateTime.of(2026, 10, 14, 15, 20);

    @Test
    void 조회_결과_한_줄의_값을_후보_결과로_옮기고_항목_종류는_문자열로_바꾼다() {
        // given: ID들에 일부러 다른 숫자를 넣음. 같은 숫자면 서로 바뀌어 옮겨져도 티가 안 나서 실수를 못 잡음
        CandidateRow row = new CandidateRow(55L, PayoutEventType.SALE_AMOUNT, 300L, 900L, 7L, 9_700L,
                CANDIDATE_CREATED_AT, 0L, null, null, 12L);

        // when
        PayoutCandidateResult sut = PayoutCandidateResult.from(row);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.candidateId()).isEqualTo(55L);
            softly.assertThat(sut.eventType()).isEqualTo("SALE_AMOUNT");
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
        CandidateRow row = row(PayoutEventType.SALE_AMOUNT, 12L);

        // when
        PayoutCandidateResult sut = PayoutCandidateResult.from(row);

        // then
        assertThat(sut.settled()).isTrue();
    }

    @Test
    void 정산_ID가_없으면_정산_전_후보다() {
        // given: payout_item과 조인되지 않은 후보
        CandidateRow row = row(PayoutEventType.SALE_AMOUNT, null);

        // when
        PayoutCandidateResult sut = PayoutCandidateResult.from(row);

        // then
        assertThat(sut.settled()).isFalse();
    }

    @Test
    void 수수료_후보는_시스템_몫이다() {
        // given: 수수료는 판매자 정산이 아니라 시스템 몫 정산에 들어감
        CandidateRow row = row(PayoutEventType.SALE_FEE, 14L);

        // when
        PayoutCandidateResult sut = PayoutCandidateResult.from(row);

        // then
        assertThat(sut.systemShare()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("판매자_몫_항목_종류")
    void 판매_대금과_배송비_후보는_시스템_몫이_아니다(PayoutEventType eventType) {
        // given
        CandidateRow row = row(eventType, 12L);

        // when
        PayoutCandidateResult sut = PayoutCandidateResult.from(row);

        // then
        assertThat(sut.systemShare()).isFalse();
    }

    static Stream<PayoutEventType> 판매자_몫_항목_종류() {
        return Stream.of(PayoutEventType.SALE_AMOUNT, PayoutEventType.SALE_SHIPPING_FEE);
    }

    @Test
    void 평소_건이면_조정_정보가_없다() {
        // given: 조정 번호 0 = 평소 건
        CandidateRow row = row(PayoutEventType.SALE_AMOUNT, 12L);

        // when
        PayoutCandidateResult sut = PayoutCandidateResult.from(row);

        // then
        assertThat(sut.adjustment()).isNull();
    }

    @Test
    void 조정_건이면_조정_번호_관리자_ID_사유를_모두_담는다() {
        // given
        CandidateRow row = new CandidateRow(55L, PayoutEventType.SALE_AMOUNT, 300L, 900L, 7L, -2_000L,
                CANDIDATE_CREATED_AT, 4L, 1L, "금액 오입력 정정", 12L);

        // when
        PayoutCandidateResult sut = PayoutCandidateResult.from(row);

        // then
        assertThat(sut.adjustment()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.adjustment().adjustmentId()).isEqualTo(4L);
            softly.assertThat(sut.adjustment().adminId()).isEqualTo(1L);
            softly.assertThat(sut.adjustment().reason()).isEqualTo("금액 오입력 정정");
        });
    }

    // 항목 종류와 정산 ID만 바꾼 조회 결과 (평소 건)
    private CandidateRow row(PayoutEventType eventType, Long payoutId) {
        return new CandidateRow(55L, eventType, 300L, 900L, 7L, 9_700L, CANDIDATE_CREATED_AT, 0L, null, null, payoutId);
    }
}
