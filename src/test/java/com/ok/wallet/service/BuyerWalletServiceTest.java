package com.ok.wallet.service;

import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.repository.BuyerWalletRepository;
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
class BuyerWalletServiceTest {
    @Mock
    BuyerWalletRepository buyerWalletRepository;

    @InjectMocks
    BuyerWalletService buyerWalletService;

    @Test
    void 지갑이_없으면_구매자_지갑을_생성한다() {
        // Given
        Long memberId = 1L;
        given(buyerWalletRepository.existsByMemberId(memberId)).willReturn(false);

        // When
        buyerWalletService.create(memberId);

        // Then
        ArgumentCaptor<BuyerWallet> captor = ArgumentCaptor.forClass(BuyerWallet.class);
        then(buyerWalletRepository).should().save(captor.capture());
        BuyerWallet saved = captor.getValue();
        assertThat(saved.getMemberId()).isEqualTo(memberId);
        assertThat(saved.getBalance()).isZero();
    }

    @Test
    void 지갑이_이미_있으면_저장하지_않는다() {
        // Given
        Long memberId = 1L;
        given(buyerWalletRepository.existsByMemberId(memberId)).willReturn(true);

        // When
        buyerWalletService.create(memberId);

        // Then
        then(buyerWalletRepository).should(never()).save(any(BuyerWallet.class));
    }

    @Test
    void 저장에_실패하면_예외를_그대로_전파한다() {
        // Given
        Long memberId = 1L;
        given(buyerWalletRepository.existsByMemberId(memberId)).willReturn(false);
        given(buyerWalletRepository.save(any(BuyerWallet.class)))
                .willThrow(new DataIntegrityViolationException("duplicate buyer wallet"));

        // When & Then
        assertThatThrownBy(() -> buyerWalletService.create(memberId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
