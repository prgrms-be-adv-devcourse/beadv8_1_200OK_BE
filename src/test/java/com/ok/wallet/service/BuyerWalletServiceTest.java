package com.ok.wallet.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.repository.BuyerWalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 동시 요청에서 각 트랜잭션이 실제로 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않음
class BuyerWalletServiceTest extends AbstractIntegrationTest {
    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    BuyerWalletRepository buyerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 원장은 지갑을 FK로 참조하므로 먼저 지운다. 원장 저장소에는 삭제 메서드가 없어 SQL로 정리한다.
        jdbcTemplate.update("DELETE FROM buyer_wallet_ledger");
        buyerWalletRepository.deleteAll();
    }

    @Test
    void 지갑이_없으면_생성해서_반환한다() {
        // Given
        Long memberId = 1L;

        // When
        BuyerWallet wallet = buyerWalletService.getOrCreate(memberId);

        // Then
        assertThat(wallet.getMemberId()).isEqualTo(memberId);
        assertThat(wallet.getBalance()).isZero();
        assertThat(countBuyerWallets(memberId)).isEqualTo(1);
    }

    @Test
    void 지갑이_있으면_기존_지갑을_반환한다() {
        // Given
        Long memberId = 2L;
        BuyerWallet existing = buyerWalletRepository.save(BuyerWallet.create(memberId));

        // When
        BuyerWallet wallet = buyerWalletService.getOrCreate(memberId);

        // Then
        assertThat(wallet.getId()).isEqualTo(existing.getId());
        assertThat(countBuyerWallets(memberId)).isEqualTo(1);
    }

    @Test
    void 동시에_요청해도_지갑은_하나만_생성되고_모두_같은_지갑을_반환한다() throws Exception {
        // Given
        Long memberId = 3L;
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();

        try {
            // When: 모든 스레드가 준비된 뒤 동시에 호출
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return buyerWalletService.getOrCreate(memberId).getId();
                }));
            }
            ready.await();
            start.countDown();

            List<Long> walletIds = new ArrayList<>();
            for (Future<Long> result : results) {
                walletIds.add(result.get(10, TimeUnit.SECONDS)); // 스레드에서 예외가 나면 여기서 실패한다
            }

            // Then
            assertThat(walletIds).containsOnly(walletIds.get(0));
            assertThat(countBuyerWallets(memberId)).isEqualTo(1);
        } finally {
            // 중간에 실패해도 스레드가 남아 DB 커넥션을 잡고 있지 않도록 정리한다
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 충전하면_잔액이_늘고_원장이_기록된다() {
        // Given
        Long memberId = 10L;
        buyerWalletService.getOrCreate(memberId);

        // When
        buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-1");

        // Then
        assertThat(balanceOf(memberId)).isEqualTo(10_000);
        assertThat(countLedgers(memberId)).isEqualTo(1);
        assertThat(latestBalanceAfter(memberId)).isEqualTo(balanceOf(memberId));
    }

    @Test
    void 같은_근거_키로_다시_충전하면_예외_없이_한_번만_반영된다() {
        // Given
        Long memberId = 11L;
        buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-1");

        // When: 같은 결제 건으로 재요청
        buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-1");

        // Then
        assertThat(balanceOf(memberId)).isEqualTo(10_000);
        assertThat(countLedgers(memberId)).isEqualTo(1);
    }

    @Test
    void 지갑이_없으면_생성한_뒤_충전한다() {
        // Given
        Long memberId = 12L;

        // When
        buyerWalletService.charge(memberId, 5_000, "CHARGE:pay-1");

        // Then
        assertThat(countBuyerWallets(memberId)).isEqualTo(1);
        assertThat(balanceOf(memberId)).isEqualTo(5_000);
    }

    @Test
    void 같은_지갑에_동시에_충전해도_모든_금액이_반영된다() throws Exception {
        // Given
        Long memberId = 13L;
        buyerWalletService.getOrCreate(memberId);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try {
            // When: 서로 다른 결제 건 10개가 동시에 1,000원씩 충전
            for (int i = 0; i < threadCount; i++) {
                String referenceKey = "CHARGE:pay-" + i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    buyerWalletService.charge(memberId, 1_000, referenceKey);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS); // 스레드에서 예외가 나면 여기서 실패한다
            }

            // Then: 덮어써졌다면 10,000원보다 적게 남는다
            assertThat(balanceOf(memberId)).isEqualTo(10_000);
            assertThat(countLedgers(memberId)).isEqualTo(threadCount);
            assertThat(latestBalanceAfter(memberId)).isEqualTo(10_000);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 다른_지갑에_이미_쓰인_근거_키로_충전하면_무시하지_않고_실패한다() {
        // Given
        buyerWalletService.charge(20L, 10_000, "CHARGE:pay-1");

        // When & Then: 조용히 넘어가지 않고 오류로 드러난다
        assertThatThrownBy(() -> buyerWalletService.charge(21L, 10_000, "CHARGE:pay-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(balanceOf(20L)).isEqualTo(10_000);
    }

    private long countBuyerWallets(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM buyer_wallet WHERE member_id = ?",
                Long.class, memberId);
    }

    private long balanceOf(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }

    private long countLedgers(Long memberId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM buyer_wallet_ledger l
            JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
            WHERE w.member_id = ?
            """, Long.class, memberId);
    }

    private long latestBalanceAfter(Long memberId) {
        return jdbcTemplate.queryForObject("""
            SELECT l.balance_after FROM buyer_wallet_ledger l
            JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
            WHERE w.member_id = ?
            ORDER BY l.id DESC LIMIT 1
            """, Long.class, memberId);
    }
}
