package com.ok.wallet.repository;

import com.ok.common.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.BuyerWallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 원장은 BuyerWallet 메서드로만 만들 수 있고, 지갑이 잘못된 값을 미리 막는다.
// 여기서는 애플리케이션 검증이 빠져도 DB 제약이 막는지 보려고 SQL로 직접 저장한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class BuyerWalletLedgerRepositoryTest {
    @Autowired
    BuyerWalletLedgerRepository buyerWalletLedgerRepository;

    @Autowired
    BuyerWalletRepository buyerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    Long walletId;

    @BeforeEach
    void setUp() {
        walletId = buyerWalletRepository.saveAndFlush(BuyerWallet.create(1L)).getId();
    }

    @Test
    void 근거_키가_같은_원장은_저장되지_않는다() {
        // Given
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1");

        // When & Then
        assertThatThrownBy(() -> insertLedger(walletId, "CHARGE", 10_000, 20_000, "CHARGE:pay-1"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_buyer_wallet_ledger_reference_key");
    }

    @Test
    void 금액이_0_이하인_원장은_저장되지_않는다() {
        assertThatThrownBy(() -> insertLedger(walletId, "CHARGE", 0, 0, "CHARGE:pay-2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_buyer_wallet_ledger_amount");
    }

    @Test
    void 변경_후_잔액이_음수인_원장은_저장되지_않는다() {
        assertThatThrownBy(() -> insertLedger(walletId, "PAYMENT", 1_000, -1_000, "PAYMENT:1"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_buyer_wallet_ledger_balance_after");
    }

    @Test
    void 지갑과_근거_키로_원장_존재_여부를_확인한다() {
        // Given
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-3");
        Long otherWalletId = buyerWalletRepository.saveAndFlush(BuyerWallet.create(2L)).getId();

        // When & Then
        assertThat(buyerWalletLedgerRepository.existsByBuyerWalletIdAndReferenceKey(walletId, "CHARGE:pay-3")).isTrue();
        assertThat(buyerWalletLedgerRepository.existsByBuyerWalletIdAndReferenceKey(walletId, "CHARGE:pay-4")).isFalse();
        // 다른 지갑의 원장은 이 지갑의 확인에 걸리지 않는다
        assertThat(buyerWalletLedgerRepository.existsByBuyerWalletIdAndReferenceKey(otherWalletId, "CHARGE:pay-3")).isFalse();
    }

    private void insertLedger(Long buyerWalletId, String type, long amount, long balanceAfter, String referenceKey) {
        jdbcTemplate.update("""
                INSERT INTO buyer_wallet_ledger
                    (buyer_wallet_id, type, amount, balance_after, reference_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))
                """, buyerWalletId, type, amount, balanceAfter, referenceKey);
    }
}
