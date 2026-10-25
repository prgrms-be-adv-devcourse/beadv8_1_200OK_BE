package com.ok.payout.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutTest {

    @Test
    void 판매자_draft로_만들면_수취인은_판매자이고_금액은_항목_합계다() {
        // given: 판매자 10의 draft (대금 9,700 + 배송비 3,000)
        PayoutSellerDraft draft = PayoutSellerDraft.create(12L, 10L, List.of(
                withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L),
                withId(PayoutCandidateItem.ofSaleShippingFee(1L, 10L, 3_000L), 2L)));

        // when
        Payout sut = Payout.forSeller(12L, draft);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getSnapshotId()).isEqualTo(12L);
            softly.assertThat(sut.getPayeeId()).isEqualTo(10L);
            softly.assertThat(sut.isSystem()).isFalse();
            softly.assertThat(sut.getAmount()).isEqualTo(12_700L);
            softly.assertThat(sut.getItems()).extracting(PayoutItem::getCandidateId).containsExactly(1L, 2L);
        });
    }

    @Test
    void 수수료_항목들로_만들면_수취인_없는_시스템_몫_1건이다() {
        // given: 판매자 10, 20의 수수료 (여러 판매자분을 합침)
        List<PayoutSystemDraftItem> draftItems = List.of(
                PayoutSystemDraftItem.create(12L, withId(PayoutCandidateItem.ofSaleFee(1L, 1001L, 10L, 300L), 3L)),
                PayoutSystemDraftItem.create(12L, withId(PayoutCandidateItem.ofSaleFee(2L, 2001L, 20L, 600L), 5L)));

        // when
        Payout sut = Payout.forSystem(12L, draftItems);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getSnapshotId()).isEqualTo(12L);
            softly.assertThat(sut.getPayeeId()).isNull();
            softly.assertThat(sut.isSystem()).isTrue();
            softly.assertThat(sut.getAmount()).isEqualTo(900L);
            softly.assertThat(sut.getItems()).extracting(PayoutItem::getCandidateId).containsExactly(3L, 5L);
        });
    }

    private PayoutCandidateItem withId(PayoutCandidateItem candidate, Long id) {
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
