package com.ok.payout;

import com.ok.payout.adapter.in.event.dto.WalletPaymentCompletedMessage;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionConflictJpaRepository;
import com.ok.payout.domain.PayoutWalletDeductionConflict;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

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

// 통합 테스트: 지갑 결제 완료 메시지 → 정산 리스너 → 차감 기록 저장까지 실제 MySQL·Kafka에서 확인
// 운영과 같은 길로 시험하려고 지갑이 보낼 메시지를 테스트가 결제 완료 토픽에 직접 넣는다 (#781, PayoutKafkaReceiveIntegrationTest와 같은 방식)
// 리스너는 별도 스레드에서 받으므로, 결과는 await()로 기다린 뒤 확인한다
// 테스트마다 겹치지 않는 주문 ID를 쓰고, 정리할 때도 그 주문의 데이터만 지운다
class PayoutWalletDeductionIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // 이 클래스 전용 주문 ID 번호대 (다른 정산 테스트의 5_000_000~9_500_000과 겹치지 않게)
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(4_000_000L);

    private static final Long BUYER_ID = 5L;
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 14, 0);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

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
    void 지갑_결제_완료_메시지를_받으면_차감_기록이_저장된다() {
        // given
        Long orderId = nextOrderId();
        WalletPaymentCompletedMessage message = new WalletPaymentCompletedMessage(orderId, BUYER_ID, 13_000L, PAID_AT);

        // when
        send(message);

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
    void 같은_지갑_결제_완료_메시지를_두_번_받아도_1건만_저장되고_뒤_메시지를_막지_않는다() {
        // given: 첫 번째 메시지로 차감 기록 1건 저장
        Long orderId = nextOrderId();
        WalletPaymentCompletedMessage message = new WalletPaymentCompletedMessage(orderId, BUYER_ID, 13_000L, PAID_AT);
        send(message);
        await().atMost(TIMEOUT).until(() -> repository.existsByOrderId(orderId));

        // when: 같은 메시지를 한 번 더 보내고(재전달 상황), 뒤이어 다른 주문 하나를 보냄
        send(message);
        Long laterOrderId = sendLaterOrderAfter(message);

        // then
        // 1) 뒤 주문이 처리될 때까지 기다림. 뒤 주문은 앞 메시지와 같은 키로 보내 같은 파티션에서 보낸 순서대로 처리되므로, 뒤 주문이 처리됐으면 중복 메시지도 처리가 끝난 것이다
        //    중복 처리에서 예외가 났다면 정산 오류 처리기가 같은 메시지를 약 31초 동안 다시 시도해 뒤 주문이 TIMEOUT 안에 처리되지 않는다
        //    (Spring 이벤트 때 "실패(FAILED)로 남지 않음"을 보던 것을 Kafka에서는 이렇게 본다)
        await().atMost(TIMEOUT).until(() -> repository.existsByOrderId(laterOrderId));
        // 2) 차감 기록은 그대로 1건
        assertThat(countDeductions(orderId)).isEqualTo(1);
        // 3) 같은 금액의 재전달은 충돌이 아니므로 충돌 기록이 없음
        assertThat(conflictRepository.findAllByOrderId(orderId)).isEmpty();
    }

    @Test
    void 금액이_음수인_지갑_결제_완료_메시지는_기록하지_않고_뒤_메시지를_막지_않는다() {
        // given: 값이 틀린 메시지 (필드가 빠진 메시지는 PayoutKafkaReceiveIntegrationTest가 확인)
        Long orderId = nextOrderId();
        WalletPaymentCompletedMessage message = new WalletPaymentCompletedMessage(orderId, BUYER_ID, -1L, PAID_AT);

        // when: 잘못된 메시지 뒤에 정상 주문 하나를 보냄
        send(message);
        Long laterOrderId = sendLaterOrderAfter(message);

        // then
        // 1) 뒤 주문이 처리될 때까지 기다림 → 같은 키라 같은 파티션에서 순서대로 처리되므로 잘못된 메시지의 처리도 끝났다는 증거. 이 뒤에야 "기록 없음"이 의미가 있다
        //    입구가 IllegalArgumentException을 던지면 정산 오류 처리기가 다시 시도하지 않고 넘기므로 TIMEOUT 안에 처리된다
        //    (값이 틀린 메시지는 다시 처리해도 결과가 같아 재처리 대상이 아님. 예외를 던지는지는 입구 단위 테스트가 확인한다)
        await().atMost(TIMEOUT).until(() -> repository.existsByOrderId(laterOrderId));
        // 2) 차감 기록은 남기지 않음
        assertThat(repository.existsByOrderId(orderId)).isFalse();
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
    void 저장된_금액과_다른_결제_완료_메시지가_두_번_와도_차감_기록은_그대로이고_충돌은_1건만_남으며_뒤_메시지를_막지_않는다() {
        // given: 15,000으로 차감 기록 저장
        Long orderId = nextOrderId();
        send(new WalletPaymentCompletedMessage(orderId, BUYER_ID, 15_000L, PAID_AT));
        await().atMost(TIMEOUT).until(() -> repository.existsByOrderId(orderId));

        // when: 같은 주문에 20,000 메시지가 두 번 오고(금액이 다른 메시지 + 그 재전달), 뒤이어 다른 주문 하나를 보냄
        // 같은 키(orderId)는 같은 파티션에 들어가고, 한 파티션 안에서는 리스너가 메시지를 하나씩 처리하므로 두 처리가 동시에 돌지 않는다
        //   (동시 처리는 UK가 막는 경로라 아래 DB 테스트가 따로 확인함)
        WalletPaymentCompletedMessage conflicting = new WalletPaymentCompletedMessage(orderId, BUYER_ID, 20_000L, PAID_AT);
        send(conflicting);
        send(conflicting);
        Long laterOrderId = sendLaterOrderAfter(conflicting);

        // then
        // 1) 뒤 주문이 처리될 때까지 기다림 → 앞의 두 메시지 처리도 끝났다는 증거
        //    충돌은 오류가 아니라 기록하고 끝나므로, 예외로 다시 시도하느라 뒤 주문이 늦어지지 않는다
        await().atMost(TIMEOUT).until(() -> repository.existsByOrderId(laterOrderId));
        // 2) 저장된 차감 금액은 먼저 온 15,000 그대로
        assertThat(repository.findByOrderId(orderId).orElseThrow().getAmount()).isEqualTo(15_000L);
        // 3) 충돌은 (저장 15,000, 받음 20,000) 1건만
        assertThat(conflictRepository.findAllByOrderId(orderId))
                .extracting(PayoutWalletDeductionConflict::getStoredAmount, PayoutWalletDeductionConflict::getReceivedAmount)
                .containsExactly(tuple(15_000L, 20_000L));
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

    // 지갑이 보내는 것처럼 결제 완료 토픽에 넣음. 키는 orderId (#339 합의)
    private void send(WalletPaymentCompletedMessage message) {
        send(message, String.valueOf(message.orderId()));
    }

    private void send(WalletPaymentCompletedMessage message, String key) {
        kafkaTemplate.send(PayoutTopics.WALLET_PAYMENT_COMPLETED, key, message);
    }

    // 앞 메시지의 처리가 끝났는지 알려고 뒤에 보내는 정상 주문
    // 앞 메시지와 같은 키로 보낸다. 같은 키는 같은 파티션에 들어가 보낸 순서대로 처리되므로, 토픽의 파티션 수가 늘어도 "뒤 주문이 처리됐으면 앞 메시지도 끝났다"가 성립한다
    private Long sendLaterOrderAfter(WalletPaymentCompletedMessage previous) {
        Long laterOrderId = nextOrderId();
        send(new WalletPaymentCompletedMessage(laterOrderId, BUYER_ID, 13_000L, PAID_AT),
                String.valueOf(previous.orderId()));
        return laterOrderId;
    }

    // 이 주문의 차감 기록 수 (리포지토리에 count가 없어 테스트 확인용으로만 SQL 사용)
    private int countDeductions(Long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payout_wallet_deduction WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }
}
