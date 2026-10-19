package com.ok.wallet.repository;

import com.ok.config.JpaConfig;
import com.ok.config.QueryDslConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.domain.BuyerWalletLedgerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 생성 시각을 정해 두고 기간·정렬을 확인하려고 원장은 SQL로 직접 넣는다 (저장소로 넣으면 현재 시각이 자동으로 들어간다)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, QueryDslConfig.class, TestcontainersConfiguration.class, BuyerWalletLedgerQueryRepository.class})
class BuyerWalletLedgerQueryRepositoryTest {
    private static final LocalDateTime OCT_10 = LocalDateTime.of(2026, 10, 10, 12, 0);
    private static final LocalDateTime OCT_15 = LocalDateTime.of(2026, 10, 15, 12, 0);
    private static final LocalDateTime OCT_20 = LocalDateTime.of(2026, 10, 20, 12, 0);

    @Autowired
    BuyerWalletLedgerQueryRepository ledgerQueryRepository;

    @Autowired
    BuyerWalletRepository buyerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long walletId;

    @BeforeEach
    void setUp() {
        walletId = buyerWalletRepository.save(BuyerWallet.create(1L)).getId();
    }

    @Test
    void 지갑의_내역을_최신순으로_조회한다() {
        // Given
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", OCT_10);
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", OCT_15);
        insertLedger(walletId, "CHARGE", 20_000, 27_000, "CHARGE:pay-2", OCT_20);

        // When
        Page<BuyerWalletLedger> page = ledgerQueryRepository.findHistory(walletId, null, null, null, PageRequest.of(0, 10));

        // Then
        assertThat(page.getContent())
                .extracting(BuyerWalletLedger::getReferenceKey)
                .containsExactly("CHARGE:pay-2", "PAYMENT:1001", "CHARGE:pay-1");
    }

    @Test
    void 같은_시각의_내역은_나중에_기록된_것이_먼저_온다() {
        // Given
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", OCT_10);
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", OCT_10);

        // When
        Page<BuyerWalletLedger> page = ledgerQueryRepository.findHistory(walletId, null, null, null, PageRequest.of(0, 10));

        // Then: 시각이 같으면 id 역순
        assertThat(page.getContent())
                .extracting(BuyerWalletLedger::getReferenceKey)
                .containsExactly("PAYMENT:1001", "CHARGE:pay-1");
    }

    @Test
    void 다른_지갑의_내역은_조회되지_않는다() {
        // Given
        Long otherWalletId = buyerWalletRepository.save(BuyerWallet.create(2L)).getId();
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", OCT_10);
        insertLedger(otherWalletId, "CHARGE", 50_000, 50_000, "CHARGE:pay-other", OCT_15);

        // When
        Page<BuyerWalletLedger> page = ledgerQueryRepository.findHistory(walletId, null, null, null, PageRequest.of(0, 10));

        // Then
        assertThat(page.getContent())
                .extracting(BuyerWalletLedger::getReferenceKey)
                .containsExactly("CHARGE:pay-1");
    }

    @Test
    void 기간은_시작_이상_종료_미만으로_조회한다() {
        // Given
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", OCT_10);
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", OCT_15);
        insertLedger(walletId, "CHARGE", 20_000, 27_000, "CHARGE:pay-2", OCT_20);

        // When: 10/15 12:00 이상, 10/20 12:00 미만
        Page<BuyerWalletLedger> page = ledgerQueryRepository.findHistory(walletId, OCT_15, OCT_20, null, PageRequest.of(0, 10));

        // Then: 시작 시각과 같은 내역은 포함, 종료 시각과 같은 내역은 제외
        assertThat(page.getContent())
                .extracting(BuyerWalletLedger::getReferenceKey)
                .containsExactly("PAYMENT:1001");
    }

    @Test
    void 유형을_주면_그_유형의_내역만_조회한다() {
        // Given
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", OCT_10);
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", OCT_15);
        insertLedger(walletId, "CHARGE", 20_000, 27_000, "CHARGE:pay-2", OCT_20);

        // When
        Page<BuyerWalletLedger> page = ledgerQueryRepository.findHistory(
                walletId, null, null, BuyerWalletLedgerType.PAYMENT, PageRequest.of(0, 10));

        // Then
        assertThat(page.getContent())
                .extracting(BuyerWalletLedger::getReferenceKey)
                .containsExactly("PAYMENT:1001");
    }

    @Test
    void 페이지_크기만큼_나눠_조회하고_전체_개수와_페이지_수를_알려준다() {
        // Given
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", OCT_10);
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", OCT_15);
        insertLedger(walletId, "CHARGE", 20_000, 27_000, "CHARGE:pay-2", OCT_20);

        // When
        Page<BuyerWalletLedger> first = ledgerQueryRepository.findHistory(walletId, null, null, null, PageRequest.of(0, 2));
        Page<BuyerWalletLedger> second = ledgerQueryRepository.findHistory(walletId, null, null, null, PageRequest.of(1, 2));

        // Then
        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(second.getContent())
                .extracting(BuyerWalletLedger::getReferenceKey)
                .containsExactly("CHARGE:pay-1");
    }

    private void insertLedger(Long buyerWalletId, String type, long amount, long balanceAfter,
                              String referenceKey, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO buyer_wallet_ledger
                    (buyer_wallet_id, type, amount, balance_after, reference_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, buyerWalletId, type, amount, balanceAfter, referenceKey, createdAt, createdAt);
    }
}
