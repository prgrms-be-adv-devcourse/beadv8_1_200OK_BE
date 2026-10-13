package com.ok.wallet.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerWalletTest {
    @Test
    void 판매자_지갑_생성_성공_테스트() {
        // Given
        Long memberId = 1L;

        // When
        SellerWallet sellerWallet = SellerWallet.create(memberId);

        // Then
        assertThat(sellerWallet.getMemberId()).isEqualTo(memberId);
        assertThat(sellerWallet.getBalance()).isZero();
    }

    @Test
    void memberId가_없으면_판매자_지갑을_생성할_수_없다() {
        // When & Then
        assertThatThrownBy(() -> SellerWallet.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memberId는 필수입니다.");
    }
}
