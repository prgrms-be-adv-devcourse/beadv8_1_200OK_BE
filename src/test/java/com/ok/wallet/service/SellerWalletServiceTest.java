package com.ok.wallet.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.domain.SellerWallet;
import com.ok.wallet.repository.SellerWalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 동시 요청에서 각 트랜잭션이 실제로 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않음
class SellerWalletServiceTest extends AbstractIntegrationTest {
    // 실제 정산 ID와 겹치지 않는 이 클래스 전용 번호대 (정산 테스트가 만든 정산의 입금 원장과 근거 키가 겹치지 않게)
    private static final long PAYOUT_ID = 9_100_001L;

    @Autowired
    SellerWalletService sellerWalletService;

    @Autowired
    SellerWalletRepository sellerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 다른 테스트가 남긴 원장(정산 배치가 발행한 이벤트로 입금된 것 등)이 있어도 영향받지 않도록 시작 전에도 정리
    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // 원장은 지갑을 FK로 참조하므로 먼저 지운다. 원장 저장소에는 삭제 메서드가 없어 SQL로 정리한다.
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM seller_wallet_ledger");
        sellerWalletRepository.deleteAll();
    }

    // getOrCreate

    @Test
    void 지갑이_없으면_생성해서_반환한다() {
        // When
        SellerWallet wallet = sellerWalletService.getOrCreate(1L);

        // Then
        assertThat(wallet.getMemberId()).isEqualTo(1L);
        assertThat(wallet.getBalance()).isZero();
        assertThat(countSellerWallets(1L)).isEqualTo(1);
    }

    @Test
    void 지갑이_있으면_기존_지갑을_반환한다() {
        // Given
        SellerWallet existing = sellerWalletRepository.save(SellerWallet.create(2L));

        // When
        SellerWallet wallet = sellerWalletService.getOrCreate(2L);

        // Then
        assertThat(wallet.getId()).isEqualTo(existing.getId());
        assertThat(countSellerWallets(2L)).isEqualTo(1);
    }

    // depositPayout

    @Test
    void 정산금을_입금하면_잔액이_늘고_원장이_기록된다() {
        // Given
        Long sellerId = 10L;
        sellerWalletService.getOrCreate(sellerId);

        // When
        sellerWalletService.depositPayout(sellerId, PAYOUT_ID, 9_700);

        // Then
        assertThat(balanceOf(sellerId)).isEqualTo(9_700);
        assertThat(countLedgers(sellerId)).isEqualTo(1);
        assertThat(latestBalanceAfter(sellerId)).isEqualTo(balanceOf(sellerId));
        assertThat(latestReferenceKey(sellerId)).isEqualTo("PAYOUT:" + PAYOUT_ID);
    }

    @Test
    void 지갑이_없으면_생성한_뒤_입금한다() {
        // Given: 판매자 승인 이벤트가 오지 않아 지갑이 없는 상태
        Long sellerId = 11L;

        // When
        sellerWalletService.depositPayout(sellerId, PAYOUT_ID, 9_700);

        // Then
        assertThat(countSellerWallets(sellerId)).isEqualTo(1);
        assertThat(balanceOf(sellerId)).isEqualTo(9_700);
    }

    @Test
    void 같은_정산을_다시_입금하면_예외_없이_한_번만_반영된다() {
        // Given
        Long sellerId = 12L;
        sellerWalletService.depositPayout(sellerId, PAYOUT_ID, 9_700);

        // When: 재발행으로 같은 정산이 다시 옴
        sellerWalletService.depositPayout(sellerId, PAYOUT_ID, 9_700);

        // Then
        assertThat(balanceOf(sellerId)).isEqualTo(9_700);
        assertThat(countLedgers(sellerId)).isEqualTo(1);
    }

    @Test
    void 정산이_다르면_같은_판매자라도_각각_입금된다() {
        // Given
        Long sellerId = 13L;

        // When
        sellerWalletService.depositPayout(sellerId, PAYOUT_ID, 9_700);
        sellerWalletService.depositPayout(sellerId, PAYOUT_ID + 1, 4_850);

        // Then
        assertThat(balanceOf(sellerId)).isEqualTo(14_550);
        assertThat(countLedgers(sellerId)).isEqualTo(2);
    }

    @Test
    void 다른_판매자에게_이미_입금된_정산이면_무시하지_않고_실패한다() {
        // Given
        sellerWalletService.depositPayout(14L, PAYOUT_ID, 9_700);

        // When & Then: 조용히 넘어가지 않고 오류로 드러난다
        assertThatThrownBy(() -> sellerWalletService.depositPayout(15L, PAYOUT_ID, 9_700))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(balanceOf(14L)).isEqualTo(9_700);
    }

    @Test
    void payoutId가_없으면_입금하지_않는다() {
        // When & Then
        assertThatThrownBy(() -> sellerWalletService.depositPayout(16L, null, 9_700))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countSellerWallets(16L)).isZero();
    }

    @Test
    void 같은_정산이_동시에_와도_한_번만_입금된다() throws Exception {
        // Given
        Long sellerId = 17L;
        sellerWalletService.getOrCreate(sellerId);
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try {
            // When: 같은 정산으로 동시에 두 번 입금
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    sellerWalletService.depositPayout(sellerId, PAYOUT_ID, 9_700);
                    return null;
                }));
            }
            ready.await();
            start.countDown();

            // 두 번째 요청은 실행 시점에 따라 정상 skip되거나 원장 유니크 제약으로 실패한다
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                }
            }

            // Then: 어느 경우든 한 번만 입금된다
            assertThat(balanceOf(sellerId)).isEqualTo(9_700);
            assertThat(countLedgers(sellerId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 서로_다른_정산이_동시에_와도_모두_입금된다() throws Exception {
        // Given
        Long sellerId = 18L;
        sellerWalletService.getOrCreate(sellerId);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try {
            // When: 서로 다른 정산 10건이 동시에 1,000원씩 입금
            for (int i = 0; i < threadCount; i++) {
                long payoutId = PAYOUT_ID + 100 + i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    sellerWalletService.depositPayout(sellerId, payoutId, 1_000);
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }

            // Then: 덮어써졌다면 10,000원보다 적게 남는다
            assertThat(balanceOf(sellerId)).isEqualTo(10_000);
            assertThat(countLedgers(sellerId)).isEqualTo(threadCount);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private long countSellerWallets(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seller_wallet WHERE member_id = ?", Long.class, memberId);
    }

    private long balanceOf(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM seller_wallet WHERE member_id = ?", Long.class, memberId);
    }

    private long countLedgers(Long memberId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM seller_wallet_ledger l
                JOIN seller_wallet w ON l.seller_wallet_id = w.id
                WHERE w.member_id = ?
                """, Long.class, memberId);
    }

    private long latestBalanceAfter(Long memberId) {
        return jdbcTemplate.queryForObject("""
                SELECT l.balance_after FROM seller_wallet_ledger l
                JOIN seller_wallet w ON l.seller_wallet_id = w.id
                WHERE w.member_id = ?
                ORDER BY l.id DESC LIMIT 1
                """, Long.class, memberId);
    }

    private String latestReferenceKey(Long memberId) {
        return jdbcTemplate.queryForObject("""
                SELECT l.reference_key FROM seller_wallet_ledger l
                JOIN seller_wallet w ON l.seller_wallet_id = w.id
                WHERE w.member_id = ?
                ORDER BY l.id DESC LIMIT 1
                """, String.class, memberId);
    }
}
