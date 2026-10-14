package com.ok.payout.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutSystemDraftItemTest {

    @Test
    void 수수료_후보로_만들면_후보의_값을_그대로_옮긴다() {
        // given
        PayoutCandidateItem fee = withId(PayoutCandidateItem.ofSaleFee(1001L, 10L, 300L), 3L);

        // when
        PayoutSystemDraftItem sut = PayoutSystemDraftItem.create(12L, fee);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getSnapshotId()).isEqualTo(12L);
            softly.assertThat(sut.getCandidateId()).isEqualTo(3L);
            softly.assertThat(sut.getEventType()).isEqualTo(PayoutEventType.SALE_FEE);
            softly.assertThat(sut.getOrderItemId()).isEqualTo(1001L);
            softly.assertThat(sut.getSellerId()).isEqualTo(10L);   // 수수료가 발생한 원래 판매자
            softly.assertThat(sut.getAmount()).isEqualTo(300L);
        });
    }

    @Test
    void 판매자_몫_후보로는_만들_수_없다() {
        // given: 판매 대금(판매자 몫) 후보
        PayoutCandidateItem saleAmount = withId(PayoutCandidateItem.ofSaleAmount(1001L, 10L, 9_700L), 1L);

        // when & then
        assertThatThrownBy(() -> PayoutSystemDraftItem.create(12L, saleAmount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("판매자 몫 정산후보는 시스템 임시 정산에 넣을 수 없습니다. candidateId=1");
    }

    private PayoutCandidateItem withId(PayoutCandidateItem candidate, Long id) {
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
