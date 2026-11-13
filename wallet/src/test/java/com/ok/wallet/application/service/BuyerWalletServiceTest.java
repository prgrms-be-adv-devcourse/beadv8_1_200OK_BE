package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.adapter.out.persistence.BuyerWalletJpaRepository;
import com.ok.wallet.application.dto.OrderPaymentResult;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
    BuyerWalletJpaRepository buyerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
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
        buyerWalletService.chargeByPayment(memberId, "payment-key-1", 10_000);

        // Then
        assertThat(balanceOf(memberId)).isEqualTo(10_000);
        assertThat(countLedgers(memberId)).isEqualTo(1);
        assertThat(latestBalanceAfter(memberId)).isEqualTo(balanceOf(memberId));
        assertThat(latestReferenceKey(memberId)).isEqualTo("CHARGE:payment-key-1");
    }

    @Test
    void 같은_결제로_다시_충전하면_예외_없이_한_번만_반영된다() {
        // Given
        Long memberId = 11L;
        buyerWalletService.chargeByPayment(memberId, "payment-key-1", 10_000);

        // When: 같은 결제 건으로 재요청
        buyerWalletService.chargeByPayment(memberId, "payment-key-1", 10_000);

        // Then
        assertThat(balanceOf(memberId)).isEqualTo(10_000);
        assertThat(countLedgers(memberId)).isEqualTo(1);
    }

    @Test
    void 지갑이_없으면_생성한_뒤_충전한다() {
        // Given
        Long memberId = 12L;

        // When
        buyerWalletService.chargeByPayment(memberId, "payment-key-1", 10_000);

        // Then
        assertThat(countBuyerWallets(memberId)).isEqualTo(1);
        assertThat(balanceOf(memberId)).isEqualTo(10_000);
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
                String paymentKey = "pay-" + i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    buyerWalletService.chargeByPayment(memberId, paymentKey, 1_000);
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
    void 같은_결제로_동시에_충전해도_한_번만_반영된다() throws Exception {
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
                    buyerWalletService.chargeByPayment(memberId, "payment-key-1", 10_000);
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
    void 다른_지갑에_이미_반영된_결제로_충전하면_무시하지_않고_실패한다() {
        // Given
        buyerWalletService.chargeByPayment(20L, "pay-1", 10_000);

        // When & Then: 조용히 넘어가지 않고 오류로 드러난다
        assertThatThrownBy(() -> buyerWalletService.chargeByPayment(21L, "pay-1", 10_000))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(balanceOf(20L)).isEqualTo(10_000);
    }

    // payForOrder

    @Test
    void 잔액이_충분하면_주문_금액만큼_차감하고_원장에_기록한다() {
        // Given
        Long memberId = 30L;
        buyerWalletService.chargeByPayment(memberId, "pay-30", 10_000);

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
        buyerWalletService.chargeByPayment(memberId, "pay-31", 10_000);

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
        buyerWalletService.chargeByPayment(memberId, "pay-33", 10_000);
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
        buyerWalletService.chargeByPayment(memberId, "pay-34", 10_000);
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
    void 같은_주문의_결제가_이미_열린_트랜잭션_안에서_동시에_와도_예외_없이_한_번만_차감된다() throws Exception {
        // Given
        Long memberId = 37L;
        buyerWalletService.chargeByPayment(memberId, "pay-37", 10_000);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderPaymentResult>> results = new ArrayList<>();

        try {
            // When: 주문 생성 리스너처럼 이미 열린 트랜잭션(기본 격리 수준) 안에서 같은 주문 결제가 동시에 처리됨
            // payForOrder가 이 트랜잭션에 합류하면 자기 격리 수준(READ COMMITTED)이 적용되지 않아,
            // 잠금 전에 잡힌 스냅샷으로 먼저 끝난 결제를 못 보고 다시 기록하려다 예외로 끝났다(#360)
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return transactionTemplate.execute(status -> buyerWalletService.payForOrder(memberId, 1_007L, 3_000));
                }));
            }
            ready.await();
            start.countDown();
            List<OrderPaymentResult> outcomes = new ArrayList<>();
            for (Future<OrderPaymentResult> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            // Then: 예외 없이 한 요청만 차감하고 나머지는 이미 결제됨을 받는다
            assertThat(outcomes).containsOnlyOnce(OrderPaymentResult.PAID);
            assertThat(outcomes).filteredOn(outcome -> outcome == OrderPaymentResult.ALREADY_PAID).hasSize(threadCount - 1);
            assertThat(balanceOf(memberId)).isEqualTo(7_000);
            assertThat(countLedgers(memberId)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 다른_회원들의_결제가_이미_열린_트랜잭션_안에서_동시에_와도_교착_없이_모두_처리된다() throws Exception {
        // given: 회원마다 10,000원을 충전하고 주문 하나(3,000원)를 이미 결제함. 이미 결제한 주문 번호는 10씩 띄워
        // 그 사이 빈 구간에 다른 회원의 새 주문이 들어가게 한다
        int memberCount = 10;
        List<Long> memberIds = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            Long memberId = 600L + i;
            memberIds.add(memberId);
            buyerWalletService.chargeByPayment(memberId, "pay-600-" + i, 10_000);
            buyerWalletService.payForOrder(memberId, paidOrderId(i), 3_000);
        }
        List<Callable<OrderPaymentResult>> payments = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            Long owner = memberIds.get(i);
            long paidOrderId = paidOrderId(i);
            // 이미 결제한 주문의 재전달: 중복 넣기의 확인 잠금이 그 주문 앞 빈 구간까지 잡을 수 있다
            payments.add(() -> buyerWalletService.payForOrder(owner, paidOrderId, 3_000));
            payments.add(() -> buyerWalletService.payForOrder(owner, paidOrderId, 3_000));
            // 같은 빈 구간에 서로 다른 두 회원의 새 주문이 동시에 들어간다. 없는 행을 잠금 읽기로 확인했다면
            // 두 트랜잭션이 같은 빈 구간을 잠근 뒤 서로의 넣기를 기다려 교착이 나는 조합이다
            Long nextMember = memberIds.get((i + 1) % memberCount);
            Long secondNextMember = memberIds.get((i + 2) % memberCount);
            payments.add(() -> buyerWalletService.payForOrder(nextMember, paidOrderId - 5, 1_000));
            payments.add(() -> buyerWalletService.payForOrder(secondNextMember, paidOrderId - 3, 1_000));
        }
        ExecutorService executor = Executors.newFixedThreadPool(payments.size());
        CountDownLatch ready = new CountDownLatch(payments.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderPaymentResult>> results = new ArrayList<>();

        try {
            // when: 주문 생성 리스너처럼 이미 열린 트랜잭션(기본 격리 수준) 안에서 모두 동시에 처리됨
            for (Callable<OrderPaymentResult> payment : payments) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return transactionTemplate.execute(status -> {
                        try {
                            return payment.call();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }));
            }
            ready.await();
            start.countDown();
            List<OrderPaymentResult> outcomes = new ArrayList<>();
            for (Future<OrderPaymentResult> result : results) {
                // 교착이 나면 한쪽이 예외로 끝나 여기서 ExecutionException이 난다
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            // then: 새 주문은 모두 차감되고 재전달은 이미 결제됨을 받는다
            assertThat(outcomes).filteredOn(outcome -> outcome == OrderPaymentResult.PAID).hasSize(memberCount * 2);
            assertThat(outcomes).filteredOn(outcome -> outcome == OrderPaymentResult.ALREADY_PAID).hasSize(memberCount * 2);
            // 회원마다 3,000원(기존) + 1,000원 × 2건(다른 빈 구간의 새 주문)이 빠진다
            assertThat(memberIds).allSatisfy(memberId -> {
                assertThat(balanceOf(memberId)).isEqualTo(5_000);
                assertThat(countLedgers(memberId)).isEqualTo(4);
            });
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_order_payment", Long.class))
                    .isEqualTo(memberCount * 3L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static long paidOrderId(int index) {
        return 6_010L + index * 10L;
    }

    @Test
    void 같은_회원의_결제와_충전이_동시에_와도_잔액이_어긋나지_않는다() throws Exception {
        // Given: 잔액 10,000원
        Long memberId = 35L;
        buyerWalletService.chargeByPayment(memberId, "pay-35", 10_000);
        int pairCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(pairCount * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try {
            // When: 1,000원 결제 5건과 1,000원 충전 5건이 동시에 들어옴
            for (int i = 0; i < pairCount; i++) {
                long orderId = 2_000L + i;
                String paymentKey = "pay-35-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    buyerWalletService.payForOrder(memberId, orderId, 1_000);
                    return null;
                }));
                results.add(executor.submit(() -> {
                    start.await();
                    buyerWalletService.chargeByPayment(memberId, paymentKey, 1_000);
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

    @Test
    void 잔액_부족으로_실패한_주문은_충전_후_같은_주문이_다시_와도_차감하지_않는다() {
        // Given: 잔액 부족으로 실패한 뒤 충전함
        Long memberId = 36L;
        buyerWalletService.payForOrder(memberId, 1_006L, 3_000);
        buyerWalletService.chargeByPayment(memberId, "pay-36", 10_000);

        // When: 같은 주문이 재전달됨
        OrderPaymentResult result = buyerWalletService.payForOrder(memberId, 1_006L, 3_000);

        // Then: 주문은 이미 실패로 처리됐으므로 돈이 빠지면 안 된다
        assertThat(result).isEqualTo(OrderPaymentResult.ALREADY_FAILED);
        assertThat(balanceOf(memberId)).isEqualTo(10_000);
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

    private String latestReferenceKey(Long memberId) {
        return jdbcTemplate.queryForObject("""
            SELECT l.reference_key FROM buyer_wallet_ledger l
            JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
            WHERE w.member_id = ?
            ORDER BY l.id DESC LIMIT 1
            """, String.class, memberId);
    }
}
