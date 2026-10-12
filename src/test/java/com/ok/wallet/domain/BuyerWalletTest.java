package com.ok.wallet.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class BuyerWalletTest {
    @Test
    void 지갑_생성_성공_테스트() {
        // Given
        Long memberId = 1L;

        // When
        BuyerWallet wallet = BuyerWallet.create(memberId);

        // Then
        assertThat(wallet.getMemberId()).isEqualTo(memberId);
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    void memberId가_없으면_지갑을_생성할_수_없다() {
        // When & Then
        assertThatThrownBy(() -> BuyerWallet.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memberId는 필수입니다.");
    }
}
