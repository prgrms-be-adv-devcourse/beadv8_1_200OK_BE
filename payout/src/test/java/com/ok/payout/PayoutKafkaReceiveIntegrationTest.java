package com.ok.payout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.adapter.out.persistence.PayoutCandidateItemJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.testsupport.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 주문·지갑이 Kafka로 보낸 메시지를 정산 리스너가 받아 정산 후보·차감 기록을 만드는지 확인 (#339)
// - 주문·지갑 코드는 쓰지 않고, 보내는 쪽이 보낼 JSON(#339 "정산이 받는 JSON 약속")을 테스트가 토픽에 직접 넣는다
// - 리스너는 별도 스레드에서 받으므로 결과는 await()로 기다린 뒤 리포지토리로 확인한다
// - 테스트마다 겹치지 않는 주문 번호를 쓰고, 정리할 때도 그 주문의 데이터만 지운다
class PayoutKafkaReceiveIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    // 이 클래스 전용 주문 번호대 (다른 정산 통합 테스트는 2_000_000 이상을 씀). 주문 상품 번호에도 같은 값을 쓴다
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(1_000_000L);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private PayoutCandidateItemJpaRepository candidateRepository;

    @Autowired
    private PayoutWalletDeductionJpaRepository deductionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 이 테스트에서 쓴 주문 번호 (정리할 때 이 주문의 행만 지움)
    private final List<Long> usedOrderIds = new ArrayList<>();

    // 리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행
    @AfterEach
    void tearDown() {
        for (Long orderId : usedOrderIds) {
            jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id = ?", orderId);
        }
        usedOrderIds.clear();
    }

    @Test
    void 구매확정을_Kafka로_받으면_정산_후보가_생긴다() {
        // given: 상품 10,000원, 판매자 묶음 배송비 3,000원
        long orderId = nextOrderId();

        // when
        sendPurchaseConfirmed(orderId, 10L, 10_000L, 3_000L);

        // then: 대금·수수료·배송비 후보 3건이 생김
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(candidateRepository.findAllByOrderId(orderId))
                        .extracting(PayoutCandidateItem::getEventType)
                        .containsExactlyInAnyOrder(PayoutEventType.SALE_AMOUNT, PayoutEventType.SALE_FEE,
                                PayoutEventType.SALE_SHIPPING_FEE));
    }

    @Test
    void 같은_구매확정을_두_번_받아도_후보는_한_번만_생긴다() {
        // given: 처음 받은 구매확정이 처리될 때까지 기다림
        long orderId = nextOrderId();
        sendPurchaseConfirmed(orderId, 10L, 10_000L, 0L);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(candidateRepository.findAllByOrderId(orderId)).hasSize(2));

        // when: 같은 메시지를 다시 보내고, 뒤이어 다른 주문 하나를 보냄
        sendPurchaseConfirmed(orderId, 10L, 10_000L, 0L);
        long laterOrderId = nextOrderId();
        sendPurchaseConfirmed(laterOrderId, 10L, 10_000L, 0L);

        // then: 뒤에 보낸 주문이 처리될 때까지 기다린 뒤 확인한다
        // 테스트 브로커는 파티션이 하나라 메시지가 보낸 순서대로 처리되므로, 뒤 주문이 처리됐으면 중복 메시지도 이미 처리된 것이다
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(candidateRepository.findAllByOrderId(laterOrderId)).hasSize(2));
        assertThat(candidateRepository.findAllByOrderId(orderId)).hasSize(2);
    }

    @Test
    void 결제_완료를_Kafka로_받으면_차감_기록이_생긴다() {
        // given
        long orderId = nextOrderId();
        Map<String, Object> message = Map.of(
                "orderId", orderId,
                "buyerId", 5L,
                "amount", 13_000L,
                "paidAt", "2026-10-30T14:00:00");

        // when
        kafkaTemplate.send(PayoutTopics.WALLET_PAYMENT_COMPLETED, String.valueOf(orderId), message);

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deductionRepository.findByOrderId(orderId)).isPresent());
        PayoutWalletDeduction sut = deductionRepository.findByOrderId(orderId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getBuyerId()).isEqualTo(5L);
            softly.assertThat(sut.getAmount()).isEqualTo(13_000L);
        });
    }

    @Test
    void 필드가_빠진_구매확정_메시지는_정산후보를_만들지_않고_다음_메시지를_처리한다() {
        // given: orderItemId가 빠진 JSON (record는 빠진 필드를 null로 읽는다)
        long badOrderId = nextOrderId();
        long goodOrderId = nextOrderId();
        Map<String, Object> badMessage = Map.of(
                "orderId", badOrderId,
                "sellerId", 10L,
                "totalItemPrice", 10_000L,
                "sellerShippingPrice", 0L);

        // when: 같은 키(같은 파티션)로 잘못된 메시지 뒤에 정상 메시지를 보냄
        kafkaTemplate.send(PayoutTopics.ORDER_PRODUCT_CONFIRMED, "same-key", badMessage);
        Map<String, Object> goodMessage = Map.of(
                "orderId", goodOrderId,
                "orderItemId", goodOrderId,
                "sellerId", 10L,
                "totalItemPrice", 10_000L,
                "sellerShippingPrice", 0L);
        kafkaTemplate.send(PayoutTopics.ORDER_PRODUCT_CONFIRMED, "same-key", goodMessage);

        // then: 정상 메시지가 처리되면, 잘못된 메시지는 저장되지 않고 뒤 메시지를 막지 않은 것이다
        // (이 결과는 #421 전에도 같았다. 다시 시도하지 않고 바로 넘기는지는 입구 단위 테스트가 확인한다)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(candidateRepository.findAllByOrderId(goodOrderId)).hasSize(2));
        assertThat(candidateRepository.findAllByOrderId(badOrderId)).isEmpty();
    }

    @Test
    void 필드가_빠진_지갑_결제_메시지는_차감_기록을_남기지_않고_다음_메시지를_처리한다() {
        // given: buyerId가 빠진 JSON
        long badOrderId = nextOrderId();
        long goodOrderId = nextOrderId();
        Map<String, Object> badMessage = Map.of(
                "orderId", badOrderId,
                "amount", 13_000L,
                "paidAt", "2026-10-30T14:00:00");
        Map<String, Object> goodMessage = Map.of(
                "orderId", goodOrderId,
                "buyerId", 5L,
                "amount", 13_000L,
                "paidAt", "2026-10-30T14:00:00");

        // when
        kafkaTemplate.send(PayoutTopics.WALLET_PAYMENT_COMPLETED, "same-key", badMessage);
        kafkaTemplate.send(PayoutTopics.WALLET_PAYMENT_COMPLETED, "same-key", goodMessage);

        // then: 정상 메시지가 처리되면, 잘못된 메시지는 기록되지 않고 뒤 메시지를 막지 않은 것이다
        // (이 결과는 #421 전에도 같았다. 다시 시도하지 않고 바로 넘기는지는 입구 단위 테스트가 확인한다)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(deductionRepository.findByOrderId(goodOrderId)).isPresent());
        assertThat(deductionRepository.findByOrderId(badOrderId)).isEmpty();
    }

    // 주문이 보낼 구매확정 JSON. 키는 orderItemId (#339 합의). 이 테스트는 주문 하나에 상품 하나라 주문 상품 번호 = 주문 번호
    private void sendPurchaseConfirmed(long orderId, Long sellerId, Long totalItemPrice, Long sellerShippingPrice) {
        Map<String, Object> message = Map.of(
                "orderId", orderId,
                "orderItemId", orderId,
                "sellerId", sellerId,
                "totalItemPrice", totalItemPrice,
                "sellerShippingPrice", sellerShippingPrice);
        kafkaTemplate.send(PayoutTopics.ORDER_PRODUCT_CONFIRMED, String.valueOf(orderId), message);
    }

    private long nextOrderId() {
        long orderId = ORDER_ID_SEQ.incrementAndGet();
        usedOrderIds.add(orderId);
        return orderId;
    }
}
