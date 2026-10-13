package com.ok.wallet.service;

import com.ok.wallet.domain.SellerWallet;
import com.ok.wallet.repository.SellerWalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SellerWalletServiceTest {
    @Mock
    SellerWalletRepository sellerWalletRepository;

    @InjectMocks
    SellerWalletService sellerWalletService;

    @Test
    void 판매자_지갑이_없으면_잔액_0으로_생성한다() {
        // Given
        Long memberId = 1L;
        given(sellerWalletRepository.existsByMemberId(memberId)).willReturn(false);

        // When
        sellerWalletService.create(memberId);

        // Then
        ArgumentCaptor<SellerWallet> captor = ArgumentCaptor.forClass(SellerWallet.class);
        verify(sellerWalletRepository).save(captor.capture());
        SellerWallet saved = captor.getValue();
        assertThat(saved.getMemberId()).isEqualTo(memberId);
        assertThat(saved.getBalance()).isZero();
    }

    @Test
    void 판매자_지갑이_이미_있으면_생성하지_않는다() {
        // Given
        Long memberId = 1L;
        given(sellerWalletRepository.existsByMemberId(memberId)).willReturn(true);

        // When
        sellerWalletService.create(memberId);

        // Then
        verify(sellerWalletRepository, never()).save(any());
    }

    @Test
    void 저장에_실패하면_예외를_그대로_전파한다() {
        // Given
        Long memberId = 3L;
        given(sellerWalletRepository.existsByMemberId(memberId)).willReturn(false);
        given(sellerWalletRepository.save(any(SellerWallet.class)))
                .willThrow(new DataIntegrityViolationException("duplicate seller wallet"));

        // When & Then
        assertThatThrownBy(() -> sellerWalletService.create(memberId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
