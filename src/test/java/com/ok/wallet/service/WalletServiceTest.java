package com.ok.wallet.service;

import com.ok.wallet.domain.Wallet;
import com.ok.wallet.domain.WalletType;
import com.ok.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {
    @Mock
    WalletRepository walletRepository;

    @InjectMocks
    WalletService walletService;

    @Test
    void 지갑이_없으면_구매자_지갑을_생성한다() {
        // Given
        Long memberId = 1L;
        given(walletRepository.existsByMemberIdAndType(memberId, WalletType.BUYER)).willReturn(false);

        // When
        walletService.createBuyerWallet(memberId);

        // Then
        ArgumentCaptor<Wallet> captor = ArgumentCaptor.forClass(Wallet.class);
        then(walletRepository).should().save(captor.capture());
        Wallet saved = captor.getValue();
        assertThat(saved.getMemberId()).isEqualTo(memberId);
        assertThat(saved.getType()).isEqualTo(WalletType.BUYER);
        assertThat(saved.getBalance()).isZero();
    }

    @Test
    void 지갑이_이미_있으면_저장하지_않는다() {
        // Given
        Long memberId = 1L;
        given(walletRepository.existsByMemberIdAndType(memberId, WalletType.BUYER)).willReturn(true);

        // When
        walletService.createBuyerWallet(memberId);

        // Then
        then(walletRepository).should(never()).save(any(Wallet.class));
    }

    @Test
    void 저장에_실패하면_예외를_그대로_전파한다() {
        // Given
        Long memberId = 1L;
        given(walletRepository.existsByMemberIdAndType(memberId, WalletType.BUYER)).willReturn(false);
        given(walletRepository.save(any(Wallet.class)))
                .willThrow(new DataIntegrityViolationException("duplicate wallet"));

        // When & Then
        assertThatThrownBy(() -> walletService.createBuyerWallet(memberId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
