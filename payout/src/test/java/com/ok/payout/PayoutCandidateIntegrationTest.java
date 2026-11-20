package com.ok.payout;

import com.ok.payout.adapter.in.event.dto.OrderProductConfirmedMessage;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.adapter.out.persistence.PayoutCandidateItemJpaRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 실제 Spring 컨텍스트 + MySQL·Kafka 컨테이너에서 구매확정 메시지 → 정산 리스너 → 저장까지 확인
// 운영과 같은 길로 시험하려고 주문이 보낼 메시지를 테스트가 구매확정 토픽에 직접 넣는다 (#781, PayoutKafkaReceiveIntegrationTest와 같은 방식)
// 리스너는 별도 스레드에서 받으므로, 결과는 await()로 기다린 뒤 확인한다
// 테스트마다 겹치지 않는 주문 ID를 쓰고, 정리할 때도 그 주문의 데이터만 지운다 (병렬 실행 시 간섭 방지)
class PayoutCandidateIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // 이 클래스 전용 주문항목 ID 번호대 (다른 테스트 클래스와 겹치지 않게 큰 수에서 시작)
    // AtomicLong: 여러 스레드가 동시에 불러도 같은 번호가 나오지 않음
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(9_000_000L);

    // 이 클래스 전용 주문 ID 번호대. 배송비 후보를 (주문, 판매자)로 묶으므로 테스트마다 다른 주문을 씀
    // 9_500_000부터: PayoutSettleIntegrationTest가 order_id 8_000_000~9_000_000을 통째로 지우므로 겹치지 않게
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(9_500_000L);

    private static final Long SELLER_ID = 10L;
    private static final Long OTHER_SELLER_ID = 20L;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PayoutCandidateItemJpaRepository repository;

    // 이 테스트에서 사용한 주문 ID (정리할 때 이 주문의 행만 지움)
    private final List<Long> usedOrderIds = new ArrayList<>();

    // 이 테스트가 만든 데이터만 정리
    // (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    // 주문 ID로 지우는 이유: 배송비 행은 order_item_id가 NULL이라 주문항목 ID로는 지울 수 없음
    @AfterEach
    void tearDown() {
        for (Long orderId : usedOrderIds) {
            jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id = ?", orderId);
        }
        usedOrderIds.clear();
    }

    @Test
    void 구매확정_메시지를_받으면_정산후보가_생성된다() {
        // given
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedMessage message = new OrderProductConfirmedMessage(orderId, orderItemId, SELLER_ID, 10_000L, 3_000L);

        // when
        send(message);

        // then: 리스너가 저장을 끝낼 때까지 최대 10초 동안 반복 확인
        // 배송비 행은 주문상품 ID가 없으므로 주문 ID로 조회
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderId(orderId))
                        .extracting(PayoutCandidateItem::getEventType, PayoutCandidateItem::getOrderItemId,
                                PayoutCandidateItem::getAmount)
                        .containsExactlyInAnyOrder(
                                tuple(PayoutEventType.SALE_AMOUNT, orderItemId, 9_700L),
                                tuple(PayoutEventType.SALE_FEE, orderItemId, 300L),
                                tuple(PayoutEventType.SALE_SHIPPING_FEE, null, 3_000L)
                        ));
    }

    @Test
    void 같은_구매확정_메시지를_두_번_받아도_정산후보_수는_그대로고_뒤_메시지를_막지_않는다() {
        // given: 첫 번째 메시지로 정산 후보 3건 생성
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedMessage message = new OrderProductConfirmedMessage(orderId, orderItemId, SELLER_ID, 10_000L, 3_000L);
        send(message);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderId(orderId)).hasSize(3));

        // when: 같은 메시지를 한 번 더 보내고(재전달 상황), 뒤이어 다른 주문 하나를 보냄
        send(message);
        Long laterOrderId = sendLaterOrderAfter(message);

        // then
        // 1) 뒤 주문이 처리될 때까지 기다림. 뒤 주문은 앞 메시지와 같은 키로 보내 같은 파티션에서 보낸 순서대로 처리되므로, 뒤 주문이 처리됐으면 중복 메시지도 처리가 끝난 것이다
        //    중복 처리에서 예외가 났다면 정산 오류 처리기가 같은 메시지를 약 31초 동안 다시 시도해 뒤 주문이 TIMEOUT 안에 처리되지 않는다
        //    (Spring 이벤트 때 "실패(FAILED)로 남지 않음"을 보던 것을 Kafka에서는 이렇게 본다)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderId(laterOrderId)).hasSize(2));
        // 2) 정산 후보는 그대로 3건
        assertThat(repository.findAllByOrderId(orderId)).hasSize(3);
    }

    @Test
    void 같은_주문상품에_관리자_조정_후보가_있어도_구매확정_메시지는_정상_처리된다() {
        // given: 같은 주문상품·같은 타입의 조정 후보가 먼저 있음 (조정 번호 1)
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();
        repository.save(PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, orderId, orderItemId, SELLER_ID, 200L, 1L, 99L, "테스트 조정"));
        OrderProductConfirmedMessage message = new OrderProductConfirmedMessage(orderId, orderItemId, SELLER_ID, 10_000L, 3_000L);

        // when
        send(message);

        // then: 조정 후보 1건 + 구매확정 후보 3건 (조정 후보 때문에 무시되지 않음)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderId(orderId))
                        .extracting(PayoutCandidateItem::getEventType, PayoutCandidateItem::getAmount,
                                PayoutCandidateItem::getAdjustmentId)
                        .containsExactlyInAnyOrder(
                                tuple(PayoutEventType.SALE_AMOUNT, 200L, 1L),      // 조정
                                tuple(PayoutEventType.SALE_AMOUNT, 9_700L, 0L),    // 구매확정
                                tuple(PayoutEventType.SALE_FEE, 300L, 0L),
                                tuple(PayoutEventType.SALE_SHIPPING_FEE, 3_000L, 0L)
                        ));
    }

    @Test
    void 잘못된_값의_구매확정_메시지는_정산후보를_만들지_않고_뒤_메시지를_막지_않는다() {
        // given: 상품 금액이 음수인 메시지 (필드가 빠진 메시지는 PayoutKafkaReceiveIntegrationTest가 확인)
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedMessage message = new OrderProductConfirmedMessage(orderId, orderItemId, SELLER_ID, -1L, 3_000L);

        // when: 잘못된 메시지 뒤에 정상 주문 하나를 보냄
        send(message);
        Long laterOrderId = sendLaterOrderAfter(message);

        // then
        // 1) 뒤 주문이 처리될 때까지 기다림. 같은 키라 같은 파티션에서 순서대로 처리되므로 이때 잘못된 메시지의 처리도 끝났다
        //    입구가 IllegalArgumentException을 던지면 정산 오류 처리기가 다시 시도하지 않고 넘기므로 TIMEOUT 안에 처리된다
        //    (값이 틀린 메시지는 다시 처리해도 결과가 같아 재처리 대상이 아님. 예외를 던지는지는 입구 단위 테스트가 확인한다)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderId(laterOrderId)).hasSize(2));
        // 2) 이 주문의 정산 후보는 저장되지 않음
        assertThat(repository.findAllByOrderId(orderId)).isEmpty();
    }

    @Test
    void 같은_판매자_묶음의_상품이_여러_개_구매확정돼도_배송비_후보는_1건만_생성된다() {
        // given: 주문 1건, 판매자 1명, 상품 2개 (묶음 배송비 3,000원이 두 메시지에 똑같이 실려 옴)
        Long orderId = nextOrderId();
        Long firstOrderItemId = nextOrderItemId();
        Long secondOrderItemId = nextOrderItemId();

        // when: 앞 상품 처리가 끝난 뒤 다음 상품을 보냄 (동시 처리는 DB UK가 막는 것을 영속성 테스트에서 확인)
        send(new OrderProductConfirmedMessage(orderId, firstOrderItemId, SELLER_ID, 10_000L, 3_000L));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderId(orderId)).hasSize(3));
        send(new OrderProductConfirmedMessage(orderId, secondOrderItemId, SELLER_ID, 5_000L, 3_000L));

        // then: 대금·수수료는 상품마다 2건씩, 배송비는 1건
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderId(orderId))
                        .extracting(PayoutCandidateItem::getEventType, PayoutCandidateItem::getOrderItemId,
                                PayoutCandidateItem::getAmount)
                        .containsExactlyInAnyOrder(
                                tuple(PayoutEventType.SALE_AMOUNT, firstOrderItemId, 9_700L),
                                tuple(PayoutEventType.SALE_FEE, firstOrderItemId, 300L),
                                tuple(PayoutEventType.SALE_AMOUNT, secondOrderItemId, 4_850L),
                                tuple(PayoutEventType.SALE_FEE, secondOrderItemId, 150L),
                                tuple(PayoutEventType.SALE_SHIPPING_FEE, null, 3_000L)
                        ));
    }

    @Test
    void 한_주문에_판매자가_여럿이면_배송비_후보는_판매자마다_1건씩_생성된다() {
        // given: 주문 1건에 판매자 2명 (판매자마다 묶음 배송비가 다름)
        Long orderId = nextOrderId();
        Long sellerOrderItemId = nextOrderItemId();
        Long otherSellerOrderItemId = nextOrderItemId();

        // when
        send(new OrderProductConfirmedMessage(orderId, sellerOrderItemId, SELLER_ID, 10_000L, 3_000L));
        send(new OrderProductConfirmedMessage(orderId, otherSellerOrderItemId, OTHER_SELLER_ID, 20_000L, 2_500L));

        // then: 배송비 후보가 판매자마다 1건, 각자의 묶음 배송비로 생성됨
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderId(orderId))
                        .filteredOn(item -> item.getEventType() == PayoutEventType.SALE_SHIPPING_FEE)
                        .extracting(PayoutCandidateItem::getSellerId, PayoutCandidateItem::getAmount)
                        .containsExactlyInAnyOrder(
                                tuple(SELLER_ID, 3_000L),
                                tuple(OTHER_SELLER_ID, 2_500L)
                        ));
    }

    // 겹치지 않는 주문 ID를 만들고, 정리 대상으로 기록
    private Long nextOrderId() {
        Long id = ORDER_ID_SEQ.incrementAndGet();
        usedOrderIds.add(id);
        return id;
    }

    // 겹치지 않는 주문항목 ID를 만듦 (정리는 주문 ID로 하므로 따로 기록하지 않음)
    private Long nextOrderItemId() {
        return ORDER_ITEM_ID_SEQ.incrementAndGet();
    }

    // 주문이 보내는 것처럼 구매확정 토픽에 넣음. 키는 orderItemId (#339 합의)
    private void send(OrderProductConfirmedMessage message) {
        send(message, String.valueOf(message.orderItemId()));
    }

    private void send(OrderProductConfirmedMessage message, String key) {
        kafkaTemplate.send(PayoutTopics.ORDER_PRODUCT_CONFIRMED, key, message);
    }

    // 앞 메시지의 처리가 끝났는지 알려고 뒤에 보내는 정상 주문 (무료배송이라 후보는 대금·수수료 2건)
    // 앞 메시지와 같은 키로 보낸다. 같은 키는 같은 파티션에 들어가 보낸 순서대로 처리되므로, 토픽의 파티션 수가 늘어도 "뒤 주문이 처리됐으면 앞 메시지도 끝났다"가 성립한다
    private Long sendLaterOrderAfter(OrderProductConfirmedMessage previous) {
        Long laterOrderId = nextOrderId();
        send(new OrderProductConfirmedMessage(laterOrderId, nextOrderItemId(), SELLER_ID, 10_000L, 0L),
                String.valueOf(previous.orderItemId()));
        return laterOrderId;
    }
}
