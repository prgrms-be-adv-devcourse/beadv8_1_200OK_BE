package com.ok.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutPolicyTest {

    @Test
    @DisplayName("판매금액의 3퍼센트를 수수료로 계산한다")
    void calculatesSaleFeeAsThreePercentOfSaleAmount() {
        // Arrange
        long saleAmount = 10_000L;

        // Act
        long fee = PayoutPolicy.calculateSaleFee(saleAmount);

        // Assert
        assertThat(fee).isEqualTo(300L);
    }

    // TODO: 원 미만 처리 방식(버림/반올림/올림) 팀 확정 필요. 현재는 버림 기준.
    @Test
    @DisplayName("원 미만은 버린다")
    void truncatesFractionalWon() {
        // Arrange
        long saleAmount = 10_050L; // 3% = 301.5원

        // Act
        long fee = PayoutPolicy.calculateSaleFee(saleAmount);

        // Assert
        assertThat(fee).isEqualTo(301L);
    }

    @Test
    @DisplayName("판매금액이 0원이면 수수료도 0원이다")
    void returnsZeroFee_whenSaleAmountIsZero() {
        // Arrange
        long saleAmount = 0L;

        // Act
        long fee = PayoutPolicy.calculateSaleFee(saleAmount);

        // Assert
        assertThat(fee).isZero();
    }
}
