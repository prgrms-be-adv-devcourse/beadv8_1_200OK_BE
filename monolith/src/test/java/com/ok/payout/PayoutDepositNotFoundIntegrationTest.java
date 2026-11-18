package com.ok.payout;

import com.ok.PaldoGotganApplication;
import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.in.batch.PayoutOutboxResendScheduler;
import com.ok.payout.adapter.out.persistence.PayoutDepositCheckJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutOutboxJpaRepository;
import com.ok.payout.application.port.in.PayoutDepositCheckUseCase;
import com.ok.payout.domain.PayoutDepositCheck;
import com.ok.payout.domain.PayoutDepositCheckStatus;
import com.ok.payout.domain.PayoutOutbox;
import com.ok.payout.domain.PayoutOutboxStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.WalletSchema;
import com.ok.wallet.adapter.in.event.WalletKafkaTopics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 정산·지갑 대사에서 진짜 지갑이 "입금 없음"으로 답할 때 정산이 약속대로 처리하는지 확인한다 (#742)
// - payout 모듈 테스트(PayoutDepositCheckIntegrationTest)는 지갑 답을 테스트가 직접 만들어 보내므로,
//   지갑이 실제로 보내는 "없음" 모양(found=false, 나머지 null)을 정산이 받아들이는지는 여기서만 확인된다
// - "없음" 상태는 지갑 원장을 지워서 만들지 않는다. 지갑 원장 입금 줄은 고치거나 지우지 않는 설계라(SellerWalletLedger @Immutable)
//   지우면 실제로 생길 수 없는 상태가 된다. 대신 지갑의 정산 입금 리스너만 멈춰 "입금 메시지가 아직 지갑에 닿지 않은" 상태를 만든다
//   대사 질문을 받는 지갑 리스너는 그룹이 달라(#679 리스너마다 그룹) 멈추지 않고 그대로 답한다
// - 정산 범위의 하한이 마지막 정산일이므로 정산 데이터는 테스트 전후로 전부 비운다 (com.ok.wallet.PayoutDepositIntegrationTest와 같은 방식)
@SpringBootTest(classes = PaldoGotganApplication.class)
class PayoutDepositNotFoundIntegrationTest extends AbstractIntegrationTest {
    // 이 클래스 전용 번호 (com.ok.wallet.PayoutDepositIntegrationTest는 600_001·6_000_001·6_001 사용)
    private static final long ORDER_ID = 760_001L;
    private static final long ORDER_ITEM_ID = 7_600_001L;
    private static final long SELLER_ID = 7_601L;
    private static final long BUYER_ID = 7_602L;

    // 판매자 몫 = 판매 대금(10,000 - 수수료 300) + 배송비 3,000
    private static final long SELLER_AMOUNT = 12_700L;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    PayoutJobRunner payoutJobRunner;

    @Autowired
    PayoutDepositCheckUseCase payoutDepositCheckUseCase;

    @Autowired
    PayoutOutboxResendScheduler payoutOutboxResendScheduler;

    @Autowired
    PayoutDepositCheckJpaRepository depositCheckRepository;

    @Autowired
    PayoutOutboxJpaRepository outboxRepository;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        // 테스트가 중간에 실패해도 멈춘 리스너를 다시 켜 둔다. 컨텍스트를 함께 쓰는 다음 테스트의 입금이 막히지 않게 하려는 것이다
        MessageListenerContainer container = walletPayoutDepositListener();
        if (!container.isRunning()) {
            container.start();
        }
        // 중간에 실패하면 다시 켠 리스너가 지운 뒤에 입금할 수 있다. 그때 남는 이 판매자 지갑은 다음 실행의 setUp이 판매자 번호로 지운다
        cleanUp();
    }

    @Test
    void 지갑이_입금_없음으로_답하면_다시_보내_입금되고_다음_대사에서_일치가_된다() throws Exception {
        // given: 지갑이 정산 입금 메시지를 아직 받지 못한 채로 정산이 끝남
        readyForPayout();
        MessageListenerContainer depositListener = walletPayoutDepositListener();
        depositListener.stop();
        assertThat(payoutJobRunner.runScheduled().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        PayoutDepositCheck check = sellerDepositCheck();
        Long payoutId = check.getPayoutId();
        // outbox가 입금 메시지를 Kafka에 보낸 뒤를 기다림. 보내기 전에 "없음" 답이 오면 이 테스트가 보려는 "보낸 줄 되돌리기"가 아니게 된다
        await().atMost(Duration.ofSeconds(10)).until(() -> outboxOf(payoutId).getStatus() == PayoutOutboxStatus.SENT);
        assertThat(sellerBalance()).isZero();

        // when: 정산이 지갑에 묻고, 진짜 지갑이 원장을 읽어 "없음"(found=false)으로 답함
        payoutDepositCheckUseCase.runCheck();

        // then: "없음"을 한 번 세고 대기로 남김
        await().atMost(Duration.ofSeconds(20)).until(() -> sellerDepositCheck().getNotFoundCount() == 1);
        PayoutDepositCheck sut = sellerDepositCheck();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
            softly.assertThat(sut.getWalletPayeeId()).isNull();
            softly.assertThat(sut.getWalletAmount()).isNull();
        });

        // then: 입금 메시지를 다시 보내도록 outbox 줄을 "보낼 것"으로 되돌리고 보낸 시각을 비움 (#620)
        PayoutOutbox outbox = outboxOf(payoutId);
        assertThat(outbox).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(outbox.getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);
            softly.assertThat(outbox.getSentAt()).isNull();
        });

        // when: 늦던 입금 메시지가 지갑에 닿고(리스너 다시 켬), 짧은 간격 재발송도 같은 메시지를 다시 보냄 (실제로는 5분마다, 여기서는 직접 부름)
        depositListener.start();
        payoutOutboxResendScheduler.resendPending();

        // then: 같은 정산의 입금 메시지가 두 번 와도 지갑에는 한 번만 들어감 (지갑은 PAYOUT:{payoutId}로 이미 입금한 정산을 건너뜀)
        assertThat(outboxOf(payoutId).getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
        await().atMost(Duration.ofSeconds(10)).until(() -> sellerBalance() == SELLER_AMOUNT);
        // 두 번째 메시지까지 처리될 동안(2초) 잔액이 그대로인지 봄. 두 번 들어갔다면 이 사이에 잔액이 25,400원이 된다
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> sellerBalance() == SELLER_AMOUNT);
        assertThat(countSellerLedgers()).isEqualTo(1);

        // when: 다음 대사가 돎 (실제로는 다음 날, 여기서는 직접 부름)
        payoutDepositCheckUseCase.runCheck();

        // then: 이번에는 지갑이 "있음"으로 답해 일치로 끝남
        await().atMost(Duration.ofSeconds(20)).until(() -> sellerDepositCheck().getStatus() == PayoutDepositCheckStatus.MATCHED);
        PayoutDepositCheck matched = sellerDepositCheck();
        assertThat(matched).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(matched.getAttemptCount()).isEqualTo(2);
            softly.assertThat(matched.getNotFoundCount()).isEqualTo(1);
            softly.assertThat(matched.getWalletPayeeId()).isEqualTo(SELLER_ID);
            softly.assertThat(matched.getWalletAmount()).isEqualTo(SELLER_AMOUNT);
        });
    }

    // 상품 10,000원, 배송비 3,000원 구매확정 후보를 15일이 지난 정산 대상으로 만든다
    private void readyForPayout() {
        // 정산은 지갑 차감 기록이 없는 주문을 정산에서 빼므로(정산 #194), 이 주문의 결제 금액(13,000원) 차감 기록을 먼저 넣는다
        givenWalletDeduction(13_000L);
        // 정산 후보 리스너는 커밋 후 실행되므로 트랜잭션 안에서 발행한다
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new OrderProductConfirmedEvent(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 10_000L, 3_000L)));
        // 정산 후보 3건(판매 대금, 수수료, 배송비)이 만들어질 때까지 기다림
        await().atMost(Duration.ofSeconds(10)).until(() -> countCandidates() == 3);
        // 정산은 구매확정 15일 뒤가 대상이므로 후보 생성 시각을 20일 전으로 옮긴다
        backdateCandidates(LocalDateTime.now().minusDays(20));
    }

    // 지갑의 정산 입금 리스너. 리스너에 id를 붙이지 않아(이름이 자동으로 붙음) 그룹 이름으로 찾는다
    private MessageListenerContainer walletPayoutDepositListener() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            if (WalletKafkaTopics.PAYOUT_DEPOSIT_GROUP.equals(container.getGroupId())) {
                return container;
            }
        }
        throw new IllegalStateException("지갑 정산 입금 리스너를 찾지 못했습니다. group=" + WalletKafkaTopics.PAYOUT_DEPOSIT_GROUP);
    }

    // cleanUp이 대사 줄을 모두 지우고, 시스템 몫 정산에는 대사 줄이 생기지 않으므로 이 테스트의 대사 줄은 판매자 몫 1건뿐이다
    private PayoutDepositCheck sellerDepositCheck() {
        List<PayoutDepositCheck> found = depositCheckRepository.findAll();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private PayoutOutbox outboxOf(Long payoutId) {
        List<PayoutOutbox> found = outboxRepository.findAllByPayoutIdIn(List.of(payoutId));
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    // 정산의 지갑 차감 기록을 직접 넣는다
    // 실제로는 지갑의 결제 완료 이벤트를 정산이 받아 저장하지만, 이 테스트는 구매확정 이후의 정산·입금 흐름만 보므로 기록만 둔다
    private void givenWalletDeduction(long amount) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                q("INSERT INTO payout_wallet_deduction (order_id, buyer_id, amount, deducted_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"),
                ORDER_ID, BUYER_ID, amount, now, now, now);
    }

    // 주문 ID로 세는 이유: 배송비 후보는 판매자 묶음 단위라 order_item_id가 NULL (주문항목 ID로 세면 배송비가 빠짐)
    private long countCandidates() {
        return jdbcTemplate.queryForObject(
                q("SELECT COUNT(*) FROM payout_candidate_items WHERE order_id = ?"), Long.class, ORDER_ID);
    }

    // 앱 시간대가 Asia/Seoul로 고정되어(#137), JdbcTemplate으로 넣은 시각과 Hibernate가 비교하는 시각의 기준이 같다
    // 배송비 후보까지 함께 옮기도록 주문 ID로 찾음 (빠지면 배송비가 정산 대상에서 제외됨)
    private void backdateCandidates(LocalDateTime createdAt) {
        jdbcTemplate.update(
                q("UPDATE payout_candidate_items SET created_at = ?, updated_at = ? WHERE order_id = ?"),
                createdAt, createdAt, ORDER_ID);
    }

    // 지갑이 아직 없으면 0으로 본다 (리스너가 처리 중이거나 멈춰 있을 수 있음)
    private long sellerBalance() {
        List<Long> balances = jdbcTemplate.queryForList(
                q("SELECT balance FROM seller_wallet WHERE member_id = ?"), Long.class, SELLER_ID);
        return balances.isEmpty() ? 0 : balances.get(0);
    }

    private long countSellerLedgers() {
        return jdbcTemplate.queryForObject(q("""
                SELECT COUNT(*) FROM seller_wallet_ledger l
                JOIN seller_wallet w ON l.seller_wallet_id = w.id
                WHERE w.member_id = ?
                """), Long.class, SELLER_ID);
    }

    // FK 때문에 자식 테이블 먼저. 정산 데이터는 전부, 후보와 지갑은 이 클래스 번호만 지운다
    // 지울 테이블 목록은 com.ok.wallet.PayoutDepositIntegrationTest와 같다 (정산 테이블이 늘면 두 곳을 함께 고친다)
    private void cleanUp() {
        jdbcTemplate.update(q("""
                DELETE l FROM seller_wallet_ledger l
                JOIN seller_wallet w ON l.seller_wallet_id = w.id
                WHERE w.member_id = ?
                """), SELLER_ID);
        jdbcTemplate.update(q("DELETE FROM seller_wallet WHERE member_id = ?"), SELLER_ID);
        jdbcTemplate.update(q("DELETE FROM payout_deposit_check"));
        // 대사 줄이 가리키는 질문 묶음이라 대사 줄 다음에 지운다
        jdbcTemplate.update(q("DELETE FROM payout_deposit_check_batch"));
        jdbcTemplate.update(q("DELETE FROM payout_outbox"));
        jdbcTemplate.update(q("DELETE FROM payout_item"));
        jdbcTemplate.update(q("DELETE FROM payout"));
        jdbcTemplate.update(q("DELETE FROM payout_seller_draft_item"));
        jdbcTemplate.update(q("DELETE FROM payout_seller_draft"));
        jdbcTemplate.update(q("DELETE FROM payout_system_draft_item"));
        // 정산 해결 기록 → 실패 기록 → 스냅샷 순서로 외래키를 따라 지운다
        jdbcTemplate.update(q("DELETE FROM payout_failure_resolution"));
        jdbcTemplate.update(q("DELETE FROM payout_failure"));
        jdbcTemplate.update(q("DELETE FROM payout_snapshot_item"));
        jdbcTemplate.update(q("DELETE FROM payout_snapshot"));
        // 배송비 후보(order_item_id NULL)까지 지우도록 주문 ID로 지움
        jdbcTemplate.update(q("DELETE FROM payout_candidate_items WHERE order_id = ?"), ORDER_ID);
        jdbcTemplate.update(q("DELETE FROM payout_wallet_deduction WHERE order_id = ?"), ORDER_ID);
    }

    // 이 테스트는 wallet·payout 두 스키마의 테이블을 함께 쓰고 접속 기본 DB는 기본 스키마라서, 테이블 이름을 스키마로 한정한다
    private static String q(String sql) {
        return sql
                .replaceAll("\\b(seller_wallet(?:_ledger)?)\\b", WalletSchema.NAME + ".$1")
                .replaceAll("\\b(payout(?:_[a-z]+)*)\\b", PayoutSchema.NAME + ".$1");
    }
}
