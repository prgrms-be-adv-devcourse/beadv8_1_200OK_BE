package com.ok.wallet.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.repository.BuyerWalletRepository;
import com.ok.wallet.service.dto.OrderPaymentResult;
import org.junit.jupiter.api.AfterEach;
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
    void 같은_근거_키로_동시에_충전해도_한_번만_반영된다() throws Exception {
        // Given
        Long memberId = 14L;
        buyerWalletService.getOrCreate(memberId);
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try {
            // When: 같은 결제 건으로 동시에 두 번 충전
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-1");
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

            // Then: 어느 경우든 잔액과 원장은 한 번만 반영된다
            assertThat(balanceOf(memberId)).isEqualTo(10_000);
            assertThat(countLedgers(memberId)).isEqualTo(1);
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

    // payForOrder

    @Test
    void 잔액이_충분하면_주문_금액만큼_차감하고_원장에_기록한다() {
        // Given
        Long memberId = 30L;
        buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-30");

        // When
        OrderPaymentResult result = buyerWalletService.payForOrder(memberId, 1_001L, 3_000);

        // Then
        assertThat(result).isEqualTo(OrderPaymentResult.PAID);
        assertThat(balanceOf(memberId)).isEqualTo(7_000);
        assertThat(latestReferenceKey(memberId)).isEqualTo("PAYMENT:1001");
        assertThat(latestBalanceAfter(memberId)).isEqualTo(7_000);
    }

    @Test
    void 잔액이_부족하면_차감하지_않고_잔액_부족을_반환한다() {
        // Given
        Long memberId = 31L;
        buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-31");

        // When
        OrderPaymentResult result = buyerWalletService.payForOrder(memberId, 1_002L, 20_000);

        // Then: 예외가 아니라 결과로 돌려줘야 같은 트랜잭션에서 실패 이벤트를 발행할 수 있다
        assertThat(result).isEqualTo(OrderPaymentResult.INSUFFICIENT_BALANCE);
        assertThat(balanceOf(memberId)).isEqualTo(10_000);
        assertThat(countLedgers(memberId)).isEqualTo(1); // 충전 원장만
    }

    @Test
    void 지갑이_없으면_잔액_0원으로_보고_잔액_부족을_반환한다() {
        // Given
        Long memberId = 32L;

        // When
        OrderPaymentResult result = buyerWalletService.payForOrder(memberId, 1_003L, 3_000);

        // Then
        assertThat(result).isEqualTo(OrderPaymentResult.INSUFFICIENT_BALANCE);
        assertThat(countBuyerWallets(memberId)).isEqualTo(1);
        assertThat(balanceOf(memberId)).isZero();
    }

    @Test
    void 같은_주문으로_다시_결제하면_차감하지_않고_이미_결제됨을_반환한다() {
        // Given
        Long memberId = 33L;
        buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-33");
        buyerWalletService.payForOrder(memberId, 1_004L, 3_000);

        // When: 주문 생성 이벤트가 재발행됨
        OrderPaymentResult result = buyerWalletService.payForOrder(memberId, 1_004L, 3_000);

        // Then
        assertThat(result).isEqualTo(OrderPaymentResult.ALREADY_PAID);
        assertThat(balanceOf(memberId)).isEqualTo(7_000);
        assertThat(countLedgers(memberId)).isEqualTo(2); // 충전 1 + 결제 1
    }

    @Test
    void 같은_주문의_결제가_동시에_와도_한_번만_차감된다() throws Exception {
        // Given
        Long memberId = 34L;
        buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-34");
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderPaymentResult>> results = new ArrayList<>();

        try {
            // When: 같은 주문 이벤트가 동시에 여러 번 처리됨
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return buyerWalletService.payForOrder(memberId, 1_005L, 3_000);
                }));
            }
            ready.await();
            start.countDown();
            List<OrderPaymentResult> outcomes = new ArrayList<>();
            for (Future<OrderPaymentResult> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            // Then: 지갑을 잠그고 확인하므로 한 요청만 차감하고 나머지는 이미 결제됨을 받는다
            assertThat(outcomes).containsOnlyOnce(OrderPaymentResult.PAID);
            assertThat(balanceOf(memberId)).isEqualTo(7_000);
            assertThat(countLedgers(memberId)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 같은_회원의_결제와_충전이_동시에_와도_잔액이_어긋나지_않는다() throws Exception {
        // Given: 잔액 10,000원
        Long memberId = 35L;
        buyerWalletService.charge(memberId, 10_000, "CHARGE:pay-35");
        int pairCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(pairCount * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try {
            // When: 1,000원 결제 5건과 1,000원 충전 5건이 동시에 들어옴
            for (int i = 0; i < pairCount; i++) {
                long orderId = 2_000L + i;
                String chargeKey = "CHARGE:pay-35-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    buyerWalletService.payForOrder(memberId, orderId, 1_000);
                    return null;
                }));
                results.add(executor.submit(() -> {
                    start.await();
                    buyerWalletService.charge(memberId, 1_000, chargeKey);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }

            // Then: 덮어써졌다면 10,000원이 아닌 값이 남는다
            assertThat(balanceOf(memberId)).isEqualTo(10_000);
            assertThat(countLedgers(memberId)).isEqualTo(1 + pairCount * 2);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private String latestReferenceKey(Long memberId) {
        return jdbcTemplate.queryForObject("""
            SELECT l.reference_key FROM buyer_wallet_ledger l
            JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
            WHERE w.member_id = ?
            ORDER BY l.id DESC LIMIT 1
            """, String.class, memberId);
    }
}
