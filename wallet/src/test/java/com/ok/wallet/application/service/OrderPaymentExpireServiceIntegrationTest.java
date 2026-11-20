package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.dto.OrderPaymentCommand;
import com.ok.wallet.application.dto.OrderPaymentExpireCommand;
import com.ok.wallet.application.port.in.OrderPaymentUseCase;
import com.ok.wallet.support.KafkaTopicReader;
import com.ok.wallet.support.WalletTestCharger;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 통합 테스트: 주문의 결제 만료 요청(expireAndSendResult)을 처리 기록 상태별로 처리하고, 주문이 받을 결과가 실제 Kafka로 나가는지 확인한다(#693).
// 결과 메시지의 모양(결제 시각 형식 등)은 OrderPaymentServiceIntegrationTest가 본다. 기록이 커밋되어야 하므로 @Transactional을 붙이지 않는다
class OrderPaymentExpireServiceIntegrationTest extends AbstractIntegrationTest {
    private static final String COMPLETED_TOPIC = "wallet.wallet-payment-completed.v1";
    private static final String FAILED_TOPIC = "wallet.wallet-payment-failed.v1";

    // 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 구매자·주문 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(8_300_000L);

    @Autowired
    OrderPaymentUseCase orderPaymentUseCase;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    private WalletTestCharger charger;

    @BeforeEach
    void setUp() {
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
        charger = new WalletTestCharger(jdbcTemplate, buyerWalletService);
    }

    @AfterEach
    void tearDown() {
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 처음_보는_주문의_만료_요청이면_돈은_움직이지_않고_만료로_기록한_뒤_결제_만료_실패를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);

        // when
        orderPaymentUseCase.expireAndSendResult(new OrderPaymentExpireCommand(orderId, buyerId, 3_000L));

        // then
        assertThat(messagesFor(FAILED_TOPIC, orderId, 1)).containsExactly(failedJson(orderId, buyerId, "PAYMENT_EXPIRED"));
        assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
        assertThat(balanceOf(buyerId)).isEqualTo(10_000);
    }

    @Test
    void 결제한_주문의_만료_요청이면_결제_완료를_다시_보내고_잔액은_그대로다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // when
        orderPaymentUseCase.expireAndSendResult(new OrderPaymentExpireCommand(orderId, buyerId, 3_000L));

        // then: 처음과 똑같은 결제 완료가 다시 나가 주문이 30분이 지났어도 결제 완료로 끝낸다
        List<String> messages = messagesFor(COMPLETED_TOPIC, orderId, 2);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isEqualTo(messages.get(0));
        assertThat(statusOf(orderId)).isEqualTo("PAID");
        assertThat(balanceOf(buyerId)).isEqualTo(7_000);
    }

    @Test
    void 잔액_부족으로_실패한_주문의_만료_요청이면_처음_실패를_다시_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // when
        orderPaymentUseCase.expireAndSendResult(new OrderPaymentExpireCommand(orderId, buyerId, 3_000L));

        // then
        assertThat(messagesFor(FAILED_TOPIC, orderId, 2)).containsExactly(
                failedJson(orderId, buyerId, "INSUFFICIENT_BALANCE"), failedJson(orderId, buyerId, "INSUFFICIENT_BALANCE"));
        assertThat(statusOf(orderId)).isEqualTo("FAILED");
    }

    @Test
    void 만료된_주문의_만료_요청이_다시_오면_만료_실패를_다시_보낸다() {
        // given: 주문은 결과가 올 때까지 5분마다 만료 요청을 다시 보낸다
        long buyerId = nextId();
        long orderId = nextId();
        orderPaymentUseCase.expireAndSendResult(new OrderPaymentExpireCommand(orderId, buyerId, 3_000L));

        // when
        orderPaymentUseCase.expireAndSendResult(new OrderPaymentExpireCommand(orderId, buyerId, 3_000L));

        // then
        assertThat(messagesFor(FAILED_TOPIC, orderId, 2)).containsExactly(
                failedJson(orderId, buyerId, "PAYMENT_EXPIRED"), failedJson(orderId, buyerId, "PAYMENT_EXPIRED"));
        assertThat(countPaymentRecords(orderId)).isOne();
    }

    @Test
    void 만료된_주문에_결제_요청이_늦게_와도_차감하지_않고_결제_만료_실패를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        orderPaymentUseCase.expireAndSendResult(new OrderPaymentExpireCommand(orderId, buyerId, 3_000L));

        // when: 주문 생성 메시지가 만료 요청보다 늦게 도착함(토픽이 달라 순서가 지켜지지 않는다)
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // then
        assertThat(messagesFor(FAILED_TOPIC, orderId, 2)).containsExactly(
                failedJson(orderId, buyerId, "PAYMENT_EXPIRED"), failedJson(orderId, buyerId, "PAYMENT_EXPIRED"));
        assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
        assertThat(balanceOf(buyerId)).isEqualTo(10_000);
    }

    @ParameterizedTest
    @MethodSource("값이_틀린_만료_요청")
    void 값이_틀린_만료_요청은_기록하지_않고_예외가_난다(Long buyerId, Long amount) {
        // given
        long orderId = nextId();
        OrderPaymentExpireCommand command = new OrderPaymentExpireCommand(orderId, buyerId, amount);

        // when & then: 다시 받아도 같으므로 지갑 오류 처리기가 다시 시도하지 않고 실패 기록에 남긴다
        assertThatThrownBy(() -> orderPaymentUseCase.expireAndSendResult(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("주문 ID, 구매자 ID, 0보다 큰 금액은 필수입니다. command=" + command);
        assertThat(countPaymentRecords(orderId)).isZero();
    }

    static Stream<Arguments> 값이_틀린_만료_요청() {
        return Stream.of(
                Arguments.of(null, 3_000L),
                Arguments.of(nextId(), null),
                Arguments.of(nextId(), 0L));
    }

    @Test
    void 결제_요청과_만료_요청이_동시에_와도_결과는_하나고_두_요청_모두_그_결과를_보낸다() throws Exception {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        CountDownLatch start = new CountDownLatch(1);

        // when: 두 요청을 같은 순간에 시작한다. 어느 쪽이 먼저 지갑을 잠그느냐에 따라 결과가 정해진다
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> pay = executor.submit(() -> {
                start.await();
                orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));
                return null;
            });
            Future<?> expire = executor.submit(() -> {
                start.await();
                orderPaymentUseCase.expireAndSendResult(new OrderPaymentExpireCommand(orderId, buyerId, 3_000L));
                return null;
            });
            start.countDown();
            pay.get();
            expire.get();
        }

        // then: 기록은 하나이고, 두 요청 모두 그 기록의 결과를 보낸다
        assertThat(countPaymentRecords(orderId)).isOne();
        if ("PAID".equals(statusOf(orderId))) {
            assertThat(messagesFor(COMPLETED_TOPIC, orderId, 2)).hasSize(2);
            assertThat(balanceOf(buyerId)).isEqualTo(7_000);
        } else {
            assertThat(statusOf(orderId)).isEqualTo("EXPIRED");
            assertThat(messagesFor(FAILED_TOPIC, orderId, 2)).containsExactly(
                    failedJson(orderId, buyerId, "PAYMENT_EXPIRED"), failedJson(orderId, buyerId, "PAYMENT_EXPIRED"));
            assertThat(balanceOf(buyerId)).isEqualTo(10_000);
        }
    }

    private List<String> messagesFor(String topic, long orderId, int expectedCount) {
        return new KafkaTopicReader(consumerFactory).valuesWithKey(topic, String.valueOf(orderId), expectedCount);
    }

    private static String failedJson(long orderId, long buyerId, String reason) {
        return "{\"orderId\":" + orderId + ",\"buyerId\":" + buyerId + ",\"reason\":\"" + reason + "\"}";
    }

    private String statusOf(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_order_payment WHERE order_id = ?", String.class, orderId);
    }

    private long countPaymentRecords(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_order_payment WHERE order_id = ?", Long.class, orderId);
    }

    private long balanceOf(long memberId) {
        Long balance = jdbcTemplate.query(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, memberId);
        return balance == null ? 0 : balance;
    }

    private static long nextId() {
        return ID_SEQ.incrementAndGet();
    }
}
