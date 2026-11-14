package com.ok.wallet.adapter.out.persistence;


import com.ok.common.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.application.dto.SellerLedgerDepositResult;
import com.ok.wallet.application.port.out.SellerWalletLedgerRepository;
import com.ok.wallet.domain.SellerWallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// 원장은 SellerWallet 메서드로만 만들 수 있고, 지갑이 잘못된 값을 미리 막는다.
// 여기서는 애플리케이션 검증이 빠져도 DB 제약이 막는지 보려고 SQL로 직접 저장한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class, SellerWalletLedgerRepositoryAdapter.class})
class SellerWalletLedgerRepositoryTest {
    @Autowired
    SellerWalletLedgerRepository sellerWalletLedgerRepository;

    @Autowired
    SellerWalletJpaRepository sellerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    Long walletId;

    @BeforeEach
    void setUp() {
        walletId = sellerWalletRepository.saveAndFlush(SellerWallet.create(1L)).getId();
    }

    @Test
    void 근거_키가_같은_원장은_저장되지_않는다() {
        // Given
        insertLedger(walletId, "PAYOUT", 9_700, 9_700, "PAYOUT:1");

        // When & Then
        assertThatThrownBy(() -> insertLedger(walletId, "PAYOUT", 9_700, 19_400, "PAYOUT:1"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_seller_wallet_ledger_reference_key");
    }

    @Test
    void 금액이_0_이하인_원장은_저장되지_않는다() {
        assertThatThrownBy(() -> insertLedger(walletId, "PAYOUT", 0, 0, "PAYOUT:2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_seller_wallet_ledger_amount");
    }

    @Test
    void 변경_후_잔액이_음수인_원장은_저장되지_않는다() {
        assertThatThrownBy(() -> insertLedger(walletId, "PAYOUT", 1_000, -1_000, "PAYOUT:3"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_seller_wallet_ledger_balance_after");
    }

    @Test
    void 지갑과_근거_키로_원장_존재_여부를_확인한다() {
        // Given
        insertLedger(walletId, "PAYOUT", 9_700, 9_700, "PAYOUT:4");
        Long otherWalletId = sellerWalletRepository.saveAndFlush(SellerWallet.create(2L)).getId();

        // When & Then
        assertThat(sellerWalletLedgerRepository.existsBySellerWalletIdAndReferenceKey(walletId, "PAYOUT:4")).isTrue();
        assertThat(sellerWalletLedgerRepository.existsBySellerWalletIdAndReferenceKey(walletId, "PAYOUT:5")).isFalse();
        // 다른 지갑의 원장은 이 지갑의 확인에 걸리지 않는다
        assertThat(sellerWalletLedgerRepository.existsBySellerWalletIdAndReferenceKey(otherWalletId, "PAYOUT:4")).isFalse();
    }

    @Test
    void 근거_키_목록으로_원장과_지갑_회원_ID를_함께_읽는다() {
        // given
        LocalDateTime depositedAt = LocalDateTime.of(2026, 11, 15, 2, 0, 3, 123_456_000);
        LocalDateTime otherDepositedAt = LocalDateTime.of(2026, 11, 14, 2, 0, 1);
        Long otherWalletId = sellerWalletRepository.saveAndFlush(SellerWallet.create(2L)).getId();
        insertLedger(walletId, "PAYOUT", 9_700, 9_700, "PAYOUT:15", depositedAt);
        insertLedger(otherWalletId, "PAYOUT", 12_000, 12_000, "PAYOUT:16", otherDepositedAt);
        insertLedger(walletId, "PAYOUT", 5_000, 14_700, "PAYOUT:17", depositedAt);

        // when
        List<SellerLedgerDepositResult> sut = sellerWalletLedgerRepository.findDepositsByReferenceKeys(
                List.of("PAYOUT:15", "PAYOUT:16", "PAYOUT:99"));

        // then: 없는 키(PAYOUT:99)와 묻지 않은 키(PAYOUT:17)는 결과에 없다
        assertThat(sut)
                .extracting(SellerLedgerDepositResult::referenceKey, SellerLedgerDepositResult::memberId,
                        SellerLedgerDepositResult::amount, SellerLedgerDepositResult::depositedAt)
                .containsExactlyInAnyOrder(
                        tuple("PAYOUT:15", 1L, 9_700L, depositedAt),
                        tuple("PAYOUT:16", 2L, 12_000L, otherDepositedAt));
    }

    @Test
    void 근거_키_목록이_비어_있으면_빈_목록을_돌려준다() {
        // given
        insertLedger(walletId, "PAYOUT", 9_700, 9_700, "PAYOUT:15");

        // when
        List<SellerLedgerDepositResult> sut = sellerWalletLedgerRepository.findDepositsByReferenceKeys(List.of());

        // then
        assertThat(sut).isEmpty();
    }

    private void insertLedger(Long sellerWalletId, String type, long amount, long balanceAfter, String referenceKey) {
        jdbcTemplate.update("""
                INSERT INTO seller_wallet_ledger
                    (seller_wallet_id, type, amount, balance_after, reference_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))
                """, sellerWalletId, type, amount, balanceAfter, referenceKey);
    }

    // 입금 시각을 정해 두고 그대로 읽히는지 보려고 생성 시각을 받는다
    private void insertLedger(Long sellerWalletId, String type, long amount, long balanceAfter, String referenceKey,
                              LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO seller_wallet_ledger
                    (seller_wallet_id, type, amount, balance_after, reference_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, sellerWalletId, type, amount, balanceAfter, referenceKey, createdAt, createdAt);
    }
}
