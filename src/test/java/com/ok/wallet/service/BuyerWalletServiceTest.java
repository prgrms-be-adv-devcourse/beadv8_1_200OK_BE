package com.ok.wallet.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.repository.BuyerWalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
        }
    }

    private long countBuyerWallets(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM buyer_wallet WHERE member_id = ?",
                Long.class, memberId);
    }
}
