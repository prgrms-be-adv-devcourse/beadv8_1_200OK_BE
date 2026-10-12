package com.ok.wallet.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void memberId가_없으면_지갑을_생성할_수_없다() {
        // When & Then
        assertThatThrownBy(() -> Wallet.create(null, WalletType.BUYER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memberId는 필수입니다.");
    }

    @Test
    void type이_없으면_지갑을_생성할_수_없다() {
        // When & Then
        assertThatThrownBy(() -> Wallet.create(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type은 필수입니다.");
    }
}
