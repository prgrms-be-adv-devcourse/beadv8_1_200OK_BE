package com.ok.payout.service.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutDraftResultTest {

    @Test
    void 성공한_판매자_수는_판매자_수에서_실패_수를_뺀_값이다() {
        // given: 판매자 3명 중 1명 실패
        PayoutDraftResult sut = new PayoutDraftResult(3, 1);

        // when & then
        assertThat(sut.draftedSellerCount()).isEqualTo(2);
    }
}
