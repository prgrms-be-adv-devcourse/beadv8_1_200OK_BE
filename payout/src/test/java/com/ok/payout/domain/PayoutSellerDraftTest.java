package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
import com.ok.payout.exception.PayoutErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutSellerDraftTest {

    @Test
    void 판매자_몫_후보로_만들면_합계는_항목_금액의_합이다() {
        // given: 판매자 10의 판매 대금 9,700 + 배송비 3,000
        List<PayoutCandidateItem> candidates = List.of(
                withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L),
                withId(PayoutCandidateItem.ofSaleShippingFee(1L, 10L, 3_000L), 2L));

        // when
        PayoutSellerDraft sut = PayoutSellerDraft.create(12L, 10L, candidates);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getSnapshotId()).isEqualTo(12L);
            softly.assertThat(sut.getSellerId()).isEqualTo(10L);
            softly.assertThat(sut.getAmount()).isEqualTo(12_700L);
            softly.assertThat(sut.getItems())
                    .extracting(PayoutSellerDraftItem::getCandidateId, PayoutSellerDraftItem::getSnapshotId)
                    .containsExactly(tuple(1L, 12L), tuple(2L, 12L));
        });
    }

    @Test
    void 수수료_후보가_섞이면_예외가_난다() {
        // given: 판매자 몫 draft에 시스템 몫(수수료) 후보를 넣음
        List<PayoutCandidateItem> candidates = List.of(
                withId(PayoutCandidateItem.ofSaleFee(1L, 1001L, 10L, 300L), 3L));

        // when & then
        assertThatThrownBy(() -> PayoutSellerDraft.create(12L, 10L, candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("시스템 몫 정산후보는 판매자 임시 정산에 넣을 수 없습니다. candidateId=3");
    }

    @Test
    void 저장되지_않은_후보로는_만들_수_없다() {
        // given: id가 없는 후보 (DB에 저장되지 않음)
        List<PayoutCandidateItem> candidates = List.of(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L));

        // when & then
        assertThatThrownBy(() -> PayoutSellerDraft.create(12L, 10L, candidates))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.MISSING_CANDIDATE_ID));
    }

    // 저장된 후보처럼 id를 채움 (setId가 없고 id는 DB 저장 시 채워지므로 단위 테스트에서 직접 넣음)
    private PayoutCandidateItem withId(PayoutCandidateItem candidate, Long id) {
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
