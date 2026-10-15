package com.ok.wallet.domain;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.exception.WalletErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void 정산금을_입금하면_잔액이_늘고_정산_입금_원장을_반환한다() {
        // Given
        SellerWallet wallet = SellerWallet.create(1L);

        // When
        SellerWalletLedger ledger = wallet.deposit(9_700, "PAYOUT:1");

        // Then
        assertThat(wallet.getBalance()).isEqualTo(9_700);
        assertThat(ledger.getType()).isEqualTo(SellerWalletLedgerType.PAYOUT);
        assertThat(ledger.getAmount()).isEqualTo(9_700);
        assertThat(ledger.getBalanceAfter()).isEqualTo(wallet.getBalance());
        assertThat(ledger.getReferenceKey()).isEqualTo("PAYOUT:1");
    }

    @Test
    void 정산금을_연속으로_입금하면_원장마다_그_시점의_잔액이_기록된다() {
        // Given
        SellerWallet wallet = SellerWallet.create(1L);

        // When
        SellerWalletLedger first = wallet.deposit(9_700, "PAYOUT:1");
        SellerWalletLedger second = wallet.deposit(4_850, "PAYOUT:2");

        // Then
        assertThat(first.getBalanceAfter()).isEqualTo(9_700);
        assertThat(second.getBalanceAfter()).isEqualTo(14_550);
        assertThat(wallet.getBalance()).isEqualTo(second.getBalanceAfter());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1_000})
    void 금액이_0_이하면_입금하지_않고_잔액도_바꾸지_않는다(long amount) {
        // Given
        SellerWallet wallet = walletWithBalance(9_700);

        // When & Then
        assertThatThrownBy(() -> wallet.deposit(amount, "PAYOUT:2"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.INVALID_AMOUNT));
        assertThat(wallet.getBalance()).isEqualTo(9_700);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void 근거_키가_없으면_입금하지_않고_잔액도_바꾸지_않는다(String referenceKey) {
        // Given
        SellerWallet wallet = walletWithBalance(9_700);

        // When & Then
        assertThatThrownBy(() -> wallet.deposit(1_000, referenceKey))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wallet.getBalance()).isEqualTo(9_700);
    }

    private SellerWallet walletWithBalance(long balance) {
        SellerWallet wallet = SellerWallet.create(1L);
        wallet.deposit(balance, "PAYOUT:setup");
        return wallet;
    }
}
