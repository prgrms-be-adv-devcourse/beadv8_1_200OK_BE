package com.ok.wallet.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WalletTest {
    @Test
    void 지갑_생성_성공_테스트() {
        // Given
        Long memberId = 1L;
        WalletType type = WalletType.BUYER;

        // When
        Wallet wallet = Wallet.create(memberId, type);

        // Then
        assertThat(wallet.getMemberId()).isEqualTo(memberId);
        assertThat(wallet.getType()).isEqualTo(type);
        assertThat(wallet.getBalance()).isZero();
    }
}