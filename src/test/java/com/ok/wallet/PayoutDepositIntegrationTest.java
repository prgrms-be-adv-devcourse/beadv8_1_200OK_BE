package com.ok.wallet;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.service.PayoutJobRunner;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 구매확정 이벤트 → 정산 후보 → 정산 배치 → 정산 생성 이벤트 → 판매자 지갑 입금까지 실제로 이어지는지 확인한다.
// - 정산은 구매확정 15일 뒤가 대상이라, 후보가 만들어진 뒤 생성 시각을 과거로 옮긴다 (PayoutSettleIntegrationTest와 같은 방식)
// - 정산 범위의 하한이 마지막 정산일이므로 정산 데이터는 테스트 전후로 전부 비운다
class PayoutDepositIntegrationTest extends AbstractIntegrationTest {
    // 이 클래스 전용 번호 (정산 테스트는 주문항목 8_000_000~, 9_000_000~ 사용)
    private static final long ORDER_ID = 600_001L;
    private static final long ORDER_ITEM_ID = 6_000_001L;
    private static final long SELLER_ID = 6_001L;
    private static final long BUYER_ID = 6_002L;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    PayoutJobRunner payoutJobRunner;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 구매확정부터_정산까지_진행되면_판매자_지갑에_정산금이_입금된다() throws Exception {
        // Given: 상품 10,000원, 배송비 3,000원 구매확정
        // 정산은 지갑 차감 기록이 없는 주문을 정산에서 빼므로(정산 #194), 이 주문의 결제 금액(13,000원) 차감 기록을 먼저 넣는다
        givenWalletDeduction(13_000L);
        // 정산 후보 리스너는 커밋 후 실행되므로 트랜잭션 안에서 발행한다
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new OrderProductConfirmedEvent(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 10_000L, 3_000L)));
        // 정산 후보 3건(판매 대금, 수수료, 배송비)이 만들어질 때까지 기다림
        await().atMost(Duration.ofSeconds(10)).until(() -> countCandidates() == 3);
        // 정산은 구매확정 15일 뒤가 대상이므로 후보 생성 시각을 20일 전으로 옮긴다
        backdateCandidates(LocalDateTime.now().minusDays(20));

        // When: 스케줄러와 같은 경로로 정산 배치 실행
        JobExecution execution = payoutJobRunner.run();

        // Then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 판매자 몫 = 판매 대금(10,000 - 수수료 300) + 배송비 3,000
        await().atMost(Duration.ofSeconds(10)).until(() -> sellerBalance() == 12_700L);
        assertThat(countSellerLedgers()).isEqualTo(1);
        assertThat(latestReferenceKey()).isEqualTo("PAYOUT:" + sellerPayoutId());
    }

    // 정산의 지갑 차감 기록을 직접 넣는다
    // 실제로는 지갑의 결제 완료 이벤트를 정산이 받아 저장하지만, 이 테스트는 구매확정 이후의 정산·입금 흐름만 보므로 기록만 둔다
    private void givenWalletDeduction(long amount) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_wallet_deduction (order_id, buyer_id, amount, deducted_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                ORDER_ID, BUYER_ID, amount, now, now, now);
    }

    // 주문 ID로 세는 이유: 배송비 후보는 판매자 묶음 단위라 order_item_id가 NULL (주문항목 ID로 세면 배송비가 빠짐)
    private long countCandidates() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payout_candidate_items WHERE order_id = ?", Long.class, ORDER_ID);
    }

    // 앱 시간대가 Asia/Seoul로 고정되어(#137), JdbcTemplate으로 넣은 시각과 Hibernate가 비교하는 시각의 기준이 같다
    // 배송비 후보까지 함께 옮기도록 주문 ID로 찾음 (빠지면 배송비가 정산 대상에서 제외됨)
    private void backdateCandidates(LocalDateTime createdAt) {
        jdbcTemplate.update(
                "UPDATE payout_candidate_items SET created_at = ?, updated_at = ? WHERE order_id = ?",
                createdAt, createdAt, ORDER_ID);
    }

    // 지갑이 아직 없으면 0으로 본다 (리스너가 처리 중일 수 있음)
    private long sellerBalance() {
        List<Long> balances = jdbcTemplate.queryForList(
                "SELECT balance FROM seller_wallet WHERE member_id = ?", Long.class, SELLER_ID);
        return balances.isEmpty() ? 0 : balances.get(0);
    }

    private long countSellerLedgers() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM seller_wallet_ledger l
                JOIN seller_wallet w ON l.seller_wallet_id = w.id
                WHERE w.member_id = ?
                """, Long.class, SELLER_ID);
    }

    private String latestReferenceKey() {
        return jdbcTemplate.queryForObject("""
                SELECT l.reference_key FROM seller_wallet_ledger l
                JOIN seller_wallet w ON l.seller_wallet_id = w.id
                WHERE w.member_id = ?
                ORDER BY l.id DESC LIMIT 1
                """, String.class, SELLER_ID);
    }

    // 판매자 몫 정산 ID (시스템 몫은 payee_id가 없음)
    private long sellerPayoutId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM payout WHERE payee_id = ?", Long.class, SELLER_ID);
    }

    // FK 때문에 자식 테이블 먼저. 정산 데이터는 전부, 후보와 지갑은 이 클래스 번호만 지운다
    private void cleanUp() {
        jdbcTemplate.update("""
                DELETE l FROM seller_wallet_ledger l
                JOIN seller_wallet w ON l.seller_wallet_id = w.id
                WHERE w.member_id = ?
                """, SELLER_ID);
        jdbcTemplate.update("DELETE FROM seller_wallet WHERE member_id = ?", SELLER_ID);
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_seller_draft_item");
        jdbcTemplate.update("DELETE FROM payout_seller_draft");
        jdbcTemplate.update("DELETE FROM payout_system_draft_item");
        // 정산 실패 기록은 스냅샷·후보를 참조하므로 그 둘보다 먼저 지운다 (정산 #194에서 생긴 테이블)
        // 빠뜨리면 스냅샷 삭제에서 FK 오류로 멈춰 후보가 남고, 뒤에 도는 정산 테스트까지 오염된다
        jdbcTemplate.update("DELETE FROM payout_failure");
        jdbcTemplate.update("DELETE FROM payout_snapshot_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot");
        // 배송비 후보(order_item_id NULL)까지 지우도록 주문 ID로 지움
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id = ?", ORDER_ID);
        jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id = ?", ORDER_ID);
    }
}
