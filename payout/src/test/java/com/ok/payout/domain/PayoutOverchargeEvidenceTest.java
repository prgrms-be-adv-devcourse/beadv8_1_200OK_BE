package com.ok.payout.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutOverchargeEvidenceTest {

    @Test
    void 넘친_금액은_후보_합계에서_결제_금액을_뺀_값이고_목록은_넣은_순서대로_돌려준다() {
        // given & when
        PayoutOverchargeEvidence sut = PayoutOverchargeEvidence.of(30_000L, 45_000L, "102", 10_000L,
                List.of("101", "배송비"), List.of("102", "103"), 2, List.of(29_000L, 31_000L));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getDeductedAmount()).isEqualTo(30_000L);
            softly.assertThat(sut.getCandidateTotal()).isEqualTo(45_000L);
            softly.assertThat(sut.getOverflowAmount()).isEqualTo(15_000L);
            softly.assertThat(sut.getFirstOverflowItem()).isEqualTo("102");
            softly.assertThat(sut.getSettledAmount()).isEqualTo(10_000L);
            softly.assertThat(sut.settledItemList()).containsExactly("101", "배송비");
            softly.assertThat(sut.aloneOverItemList()).containsExactly("102", "103");
            softly.assertThat(sut.getSellerCount()).isEqualTo(2);
            softly.assertThat(sut.conflictAmountList()).containsExactly(29_000L, 31_000L);
        });
    }

    @Test
    void 빈_목록은_빈_목록으로_돌려준다() {
        // given & when
        PayoutOverchargeEvidence sut = PayoutOverchargeEvidence.of(30_000L, 35_000L, "판별 불가", 0L,
                List.of(), List.of(), 1, List.of());

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.settledItemList()).isEmpty();
            softly.assertThat(sut.aloneOverItemList()).isEmpty();
            softly.assertThat(sut.conflictAmountList()).isEmpty();
        });
    }

    @Test
    void 초과가_풀린_지금_값은_넘친_금액이_음수다() {
        // given & when
        PayoutOverchargeEvidence sut = PayoutOverchargeEvidence.of(30_000L, 25_000L, "판별 불가", 0L,
                List.of(), List.of(), 1, List.of());

        // then
        assertThat(sut.getOverflowAmount()).isEqualTo(-5_000L);
    }

    @Test
    void 목록_값이_null이면_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> PayoutOverchargeEvidence.of(30_000L, 35_000L, "1", 0L, null, List.of(), 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("초과 청구 근거의 주문상품·목록 값은 null일 수 없습니다.");
    }
}
