package com.ok.payout;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionConflictJpaRepository;
import com.ok.payout.domain.PayoutWalletDeductionConflict;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 지갑 차감 이벤트 발행 → 정산 리스너 → 차감 기록 저장까지 실제 MySQL에서 확인
// @ApplicationModuleListener는 커밋 후 별도 스레드(비동기)에서 실행되므로, 결과는 await()로 기다린 뒤 확인한다
// 테스트마다 겹치지 않는 주문 ID를 쓰고, 정리할 때도 그 주문의 데이터만 지운다
class PayoutWalletDeductionIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // 이 클래스 전용 주문 ID 번호대 (다른 정산 테스트의 5_000_000~9_500_000과 겹치지 않게)
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(4_000_000L);

    private static final Long BUYER_ID = 5L;
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 14, 0);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PayoutWalletDeductionJpaRepository repository;

    @Autowired
    private PayoutWalletDeductionConflictJpaRepository conflictRepository;

    // 이 테스트에서 쓴 주문 ID (정리할 때 이 주문의 행만 지움)
    private final List<Long> usedOrderIds = new ArrayList<>();

    // 리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행
    @AfterEach
    void tearDown() {
        for (Long orderId : usedOrderIds) {
            jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM payout_wallet_deduction_conflict WHERE order_id = ?", orderId);
        }
        usedOrderIds.clear();
    }

    @Test
    void 지갑_차감_이벤트를_받으면_차감_기록이_저장된다() {
        // given
        Long orderId = nextOrderId();
        WalletPaymentCompletedEvent event = new WalletPaymentCompletedEvent(orderId, BUYER_ID, 13_000L, PAID_AT);

        // when
        // 지갑 쪽처럼 트랜잭션 안에서 발행 (커밋되어야 리스너가 실행됨)
        publishInTransaction(event);

        // then: 리스너가 저장을 끝낼 때까지 기다린 뒤 값 확인
        await().atMost(TIMEOUT).until(() -> repository.existsByOrderId(orderId));
        PayoutWalletDeduction sut = repository.findByOrderId(orderId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getBuyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(sut.getAmount()).isEqualTo(13_000L);
            softly.assertThat(sut.getDeductedAt()).isEqualTo(PAID_AT);
        });
    }

    @Test
    void 같은_지갑_차감_이벤트를_두_번_받아도_1건만_저장되고_실패로_남지_않는다() {
        // given: 첫 번째 이벤트로 차감 기록 1건 저장
        Long orderId = nextOrderId();
        WalletPaymentCompletedEvent event = new WalletPaymentCompletedEvent(orderId, BUYER_ID, 13_000L, PAID_AT);
        publishInTransaction(event);
        await().atMost(TIMEOUT).until(() -> repository.existsByOrderId(orderId));

        // when: 같은 이벤트를 한 번 더 발행 (재전달 상황)
        publishInTransaction(event);

        // then
        // 1) 두 번째 처리까지 완료(COMPLETED)될 때까지 기다림 → 리스너가 실제로 두 번 실행됐다는 증거
        await().atMost(TIMEOUT).until(() -> countPublications(orderId, "COMPLETED") == 2);
        // 2) 차감 기록은 그대로 1건
        assertThat(countDeductions(orderId)).isEqualTo(1);
        // 3) 중복이 실패(FAILED)로 남지 않음
        assertThat(countPublications(orderId, "FAILED")).isZero();
    }

    @Test
    void 금액이_음수인_지갑_차감_이벤트는_기록하지_않고_실패로_남지_않는다() {
        // given: 값이 틀린 이벤트 (공용 이벤트 생성자는 음수를 막지 않으므로 입구가 걸러야 한다)
        Long orderId = nextOrderId();
        WalletPaymentCompletedEvent event = new WalletPaymentCompletedEvent(orderId, BUYER_ID, -1L, PAID_AT);

        // when
        publishInTransaction(event);

        // then
        // 1) 리스너가 정상 종료(COMPLETED)할 때까지 기다림 → 처리를 끝냈다는 증거. 이 뒤에야 "기록 없음"이 의미가 있다
        await().atMost(TIMEOUT).until(() -> countPublications(orderId, "COMPLETED") == 1);
        // 2) 차감 기록은 남기지 않음
        assertThat(repository.existsByOrderId(orderId)).isFalse();
        // 3) 실패(FAILED)로 남지 않아 재처리 대상이 아님
        assertThat(countPublications(orderId, "FAILED")).isZero();
    }

    @Test
    void 같은_주문의_차감_기록은_DB에서도_두_번_저장할_수_없다() {
        // given: 리스너를 거치지 않고 저장 (동시에 두 번 처리되어 중복 확인을 둘 다 통과한 상황)
        Long orderId = nextOrderId();
        repository.save(PayoutWalletDeduction.create(orderId, BUYER_ID, 13_000L, PAID_AT));

        // when & then
        assertThatThrownBy(() -> repository.save(PayoutWalletDeduction.create(orderId, BUYER_ID, 13_000L, PAID_AT)))
                .rootCause()
                .hasMessageContaining("uk_payout_wallet_deduction_order_id");
    }

    @Test
    void 저장된_금액과_다른_차감_이벤트가_두_번_와도_차감_기록은_그대로이고_충돌은_1건만_남으며_실패로_남지_않는다() {
        // given: 15,000으로 차감 기록 저장
        Long orderId = nextOrderId();
        publishInTransaction(new WalletPaymentCompletedEvent(orderId, BUYER_ID, 15_000L, PAID_AT));
        await().atMost(TIMEOUT).until(() -> repository.existsByOrderId(orderId));

        // when: 같은 주문에 20,000 이벤트가 두 번 옴 (금액이 다른 이벤트 + 그 재전달)
        // 재전달은 첫 처리가 끝난 뒤 보냄: 리스너는 비동기라 바로 이어 보내면 두 처리가 동시에 돌아 UK에 막힌 쪽이 FAILED로 남음
        //   (동시 처리는 UK가 막는 경로라 아래 DB 테스트가 따로 확인함)
        WalletPaymentCompletedEvent conflicting = new WalletPaymentCompletedEvent(orderId, BUYER_ID, 20_000L, PAID_AT);
        publishInTransaction(conflicting);
        await().atMost(TIMEOUT).until(() -> countPublications(orderId, "COMPLETED") == 2);
        publishInTransaction(conflicting);

        // then
        // 1) 세 번 모두 처리 완료(COMPLETED)될 때까지 기다림 → 리스너가 실제로 세 번 실행됐다는 증거
        await().atMost(TIMEOUT).until(() -> countPublications(orderId, "COMPLETED") == 3);
        // 2) 저장된 차감 금액은 먼저 온 15,000 그대로
        assertThat(repository.findByOrderId(orderId).orElseThrow().getAmount()).isEqualTo(15_000L);
        // 3) 충돌은 (저장 15,000, 받음 20,000) 1건만
        assertThat(conflictRepository.findAllByOrderId(orderId))
                .extracting(PayoutWalletDeductionConflict::getStoredAmount, PayoutWalletDeductionConflict::getReceivedAmount)
                .containsExactly(tuple(15_000L, 20_000L));
        // 4) 충돌은 오류가 아니라 기록하고 끝난 것 → 실패(FAILED)로 남지 않음
        assertThat(countPublications(orderId, "FAILED")).isZero();
    }

    @Test
    void 같은_주문의_같은_받은_금액_충돌은_DB에서도_두_번_저장할_수_없다() {
        // given: 서비스를 거치지 않고 저장 (동시에 두 번 처리되어 존재 확인을 둘 다 통과한 상황)
        Long orderId = nextOrderId();
        conflictRepository.save(PayoutWalletDeductionConflict.create(orderId, 15_000L, 20_000L));

        // when & then
        assertThatThrownBy(() -> conflictRepository.save(PayoutWalletDeductionConflict.create(orderId, 15_000L, 20_000L)))
                .rootCause()
                .hasMessageContaining("uk_payout_wallet_deduction_conflict_order_id_received_amount");
    }

    // 겹치지 않는 주문 ID를 만들고, 정리 대상으로 기록
    private Long nextOrderId() {
        Long id = ORDER_ID_SEQ.incrementAndGet();
        usedOrderIds.add(id);
        return id;
    }

    // 트랜잭션 안에서 이벤트 발행 (커밋 후 리스너 실행)
    private void publishInTransaction(WalletPaymentCompletedEvent event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    // 이 주문의 차감 기록 수 (리포지토리에 count가 없어 테스트 확인용으로만 SQL 사용)
    private int countDeductions(Long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payout_wallet_deduction WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    // event_publication에서 정산 리스너가 받은 이 주문 이벤트 중 특정 상태인 건수
    // 지갑 차감 이벤트는 주문 모듈도 받을 예정이라 정산 리스너(listener_id)로 좁힘
    private int countPublications(Long orderId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication " +
                        "WHERE status = ? AND listener_id LIKE ? AND JSON_EXTRACT(serialized_event, '$.orderId') = ?",
                Integer.class,
                status, "%PayoutWalletEventListener%", orderId);
        return count == null ? 0 : count;
    }
}
