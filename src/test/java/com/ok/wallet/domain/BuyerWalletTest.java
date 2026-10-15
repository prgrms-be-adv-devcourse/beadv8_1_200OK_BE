package com.ok.wallet.domain;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.exception.WalletErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuyerWalletTest {
    @Test
    void 지갑_생성_성공_테스트() {
        // Given
        Long memberId = 1L;

        // When
        BuyerWallet buyerWallet = BuyerWallet.create(memberId);

        // Then
        assertThat(buyerWallet.getMemberId()).isEqualTo(memberId);
        assertThat(buyerWallet.getBalance()).isZero();
    }

    @Test
    void memberId가_없으면_지갑을_생성할_수_없다() {
        // When & Then
        assertThatThrownBy(() -> BuyerWallet.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memberId는 필수입니다.");
    }

    @Test
    void 충전하면_잔액이_늘고_충전_원장을_반환한다() {
        // Given
        BuyerWallet wallet = BuyerWallet.create(1L);

        // When
        BuyerWalletLedger ledger = wallet.charge(10_000, "CHARGE:pay-1");

        // Then
        assertThat(wallet.getBalance()).isEqualTo(10_000);
        assertThat(ledger.getType()).isEqualTo(BuyerWalletLedgerType.CHARGE);
        assertThat(ledger.getAmount()).isEqualTo(10_000);
        assertThat(ledger.getBalanceAfter()).isEqualTo(wallet.getBalance());
        assertThat(ledger.getReferenceKey()).isEqualTo("CHARGE:pay-1");
    }

    @Test
    void 결제하면_잔액이_줄고_결제_원장을_반환한다() {
        // Given
        BuyerWallet wallet = walletWithBalance(10_000);

        // When
        BuyerWalletLedger ledger = wallet.pay(3_000, "PAYMENT:1");

        // Then
        assertThat(wallet.getBalance()).isEqualTo(7_000);
        assertThat(ledger.getType()).isEqualTo(BuyerWalletLedgerType.PAYMENT);
        assertThat(ledger.getAmount()).isEqualTo(3_000);
        assertThat(ledger.getBalanceAfter()).isEqualTo(7_000);
    }

    @Test
    void 잔액과_같은_금액은_결제할_수_있다() {
        // Given
        BuyerWallet wallet = walletWithBalance(10_000);

        // When
        wallet.pay(10_000, "PAYMENT:1");

        // Then
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    void 잔액이_부족하면_결제하지_않고_잔액도_바꾸지_않는다() {
        // Given
        BuyerWallet wallet = walletWithBalance(10_000);

        // When & Then
        assertThatThrownBy(() -> wallet.pay(10_001, "PAYMENT:1"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.INSUFFICIENT_BALANCE));
        assertThat(wallet.getBalance()).isEqualTo(10_000);
    }

    @Test
    void 환불하면_잔액이_늘고_환불_원장을_반환한다() {
        // Given
        BuyerWallet wallet = walletWithBalance(7_000);

        // When
        BuyerWalletLedger ledger = wallet.refund(3_000, "REFUND:1");

        // Then
        assertThat(wallet.getBalance()).isEqualTo(10_000);
        assertThat(ledger.getType()).isEqualTo(BuyerWalletLedgerType.REFUND);
        assertThat(ledger.getBalanceAfter()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1_000})
    void 금액이_0_이하면_잔액을_바꾸지_않는다(long amount) {
        // Given
        BuyerWallet wallet = walletWithBalance(10_000);

        // When & Then
        assertThatThrownBy(() -> wallet.charge(amount, "CHARGE:pay-1"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.INVALID_AMOUNT));
        assertThatThrownBy(() -> wallet.pay(amount, "PAYMENT:1"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.INVALID_AMOUNT));
        assertThatThrownBy(() -> wallet.refund(amount, "REFUND:1"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.INVALID_AMOUNT));
        assertThat(wallet.getBalance()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void 근거_키가_없으면_잔액을_바꾸지_않는다(String referenceKey) {
        // Given
        BuyerWallet wallet = walletWithBalance(10_000);

        // When & Then
        assertThatThrownBy(() -> wallet.charge(1_000, referenceKey))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wallet.getBalance()).isEqualTo(10_000);
    }

    @Test
    void 연속으로_변경하면_원장마다_그_시점의_잔액이_기록된다() {
        // Given
        BuyerWallet wallet = BuyerWallet.create(1L);

        // When
        BuyerWalletLedger first = wallet.charge(10_000, "CHARGE:pay-1");
        BuyerWalletLedger second = wallet.pay(3_000, "PAYMENT:1");
        BuyerWalletLedger third = wallet.refund(1_000, "REFUND:1");

        // Then
        assertThat(first.getBalanceAfter()).isEqualTo(10_000);
        assertThat(second.getBalanceAfter()).isEqualTo(7_000);
        assertThat(third.getBalanceAfter()).isEqualTo(8_000);
        assertThat(wallet.getBalance()).isEqualTo(third.getBalanceAfter());
    }

    private BuyerWallet walletWithBalance(long balance) {
        BuyerWallet wallet = BuyerWallet.create(1L);
        wallet.charge(balance, "CHARGE:setup");
        return wallet;
    }
}
