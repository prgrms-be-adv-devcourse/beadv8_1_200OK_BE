package com.ok.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutCandidateItemTest {

    @Test
    @DisplayName("판매 수수료(SALE_FEE)는 시스템 몫이다")
    void saleFee_isSystemShare() {
        // Arrange
        PayoutCandidateItem fee = PayoutCandidateItem.ofSaleFee(1L, 10L, 300L);

        // Act & Assert
        assertThat(fee.isSystemShare()).isTrue();
    }

    @Test
    @DisplayName("판매 대금·배송비는 판매자 몫이다 (시스템 몫 아님)")
    void saleAmountAndShippingFee_areNotSystemShare() {
        // Arrange
        PayoutCandidateItem amount = PayoutCandidateItem.ofSaleAmount(1L, 10L, 9_700L);
        PayoutCandidateItem shippingFee = PayoutCandidateItem.ofSaleShippingFee(1L, 10L, 3_000L);

        // Act & Assert
        assertThat(amount.isSystemShare()).isFalse();
        assertThat(shippingFee.isSystemShare()).isFalse();
    }
}
