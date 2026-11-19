package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.dto.OrderPaymentCommand;
import com.ok.wallet.application.port.in.OrderPaymentUseCase;
import com.ok.wallet.support.KafkaTopicReader;
import com.ok.wallet.support.WalletTestCharger;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 통합 테스트: Kafka로 받은 주문(payAndSendResult)을 처리한 뒤 주문·정산이 받을 결과 메시지가 실제 Kafka에
// 약속한 모양(#602)으로 나가는지 확인한다. 받는 쪽 코드는 쓰지 않고, 토픽에 나간 JSON을 문자열 그대로 읽어 필드 이름과 값을 본다.
// 결제가 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않는다
class OrderPaymentServiceIntegrationTest extends AbstractIntegrationTest {
    private static final String COMPLETED_TOPIC = "wallet.wallet-payment-completed.v1";
    private static final String FAILED_TOPIC = "wallet.wallet-payment-failed.v1";
    // 결제 시각은 실행마다 달라 따로 꺼내 본다. 오프셋(Z, +09:00)이 붙으면 맞지 않는다
    private static final Pattern COMPLETED_JSON = Pattern.compile(
            "\\{\"orderId\":(\\d+),\"buyerId\":(\\d+),\"amount\":(\\d+),\"paidAt\":\"([0-9T:.\\-]+)\"}");

    // 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 구매자·주문 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(8_100_000L);

    @Autowired
    OrderPaymentUseCase orderPaymentUseCase;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

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
    void 잔액이_충분하면_차감하고_처리_기록_시각을_담아_완료_메시지를_주문_번호_키로_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);

        // when
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // then
        assertThat(balanceOf(buyerId)).isEqualTo(7_000);
        List<String> messages = messagesFor(COMPLETED_TOPIC, orderId, 1);
        assertThat(messages).hasSize(1);
        Matcher json = COMPLETED_JSON.matcher(messages.getFirst());
        assertThat(json.matches()).as("메시지 모양: %s", messages.getFirst()).isTrue();
        assertThat(json.group(1)).isEqualTo(String.valueOf(orderId));
        assertThat(json.group(2)).isEqualTo(String.valueOf(buyerId));
        assertThat(json.group(3)).isEqualTo("3000");
        assertThat(LocalDateTime.parse(json.group(4))).isEqualTo(recordedAt(orderId));
    }

    @Test
    void 결제하면_주문_요약을_처리_기록에_남긴다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);

        // when
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, "사과 외 3건"));

        // then
        assertThat(orderSummaryOf(orderId)).isEqualTo("사과 외 3건");
    }

    @Test
    void 주문_요약이_칸_길이를_넘어도_결제하고_잘라서_남긴다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        String longSummary = "가".repeat(200);

        // when
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, longSummary));

        // then
        assertThat(balanceOf(buyerId)).isEqualTo(7_000);
        assertThat(orderSummaryOf(orderId)).isEqualTo("가".repeat(120));
    }

    @Test
    void 잔액이_부족하면_차감하지_않고_잔액_부족_실패_메시지를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();

        // when
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // then
        assertThat(balanceOf(buyerId)).isZero();
        assertThat(messagesFor(FAILED_TOPIC, orderId, 1))
                .containsExactly(failedJson(orderId, buyerId, "INSUFFICIENT_BALANCE"));
    }

    @Test
    void 금액이_0_이하면_기록하지_않고_처리할_수_없는_요청_실패_메시지를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();

        // when
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 0L, null));

        // then: 주문이 결제 대기로 멈추지 않도록 실패를 알린다
        assertThat(messagesFor(FAILED_TOPIC, orderId, 1))
                .containsExactly(failedJson(orderId, buyerId, "INVALID_PAYMENT_REQUEST"));
        assertThat(countPaymentRecords(orderId)).isZero();
    }

    @Test
    void 같은_주문이_다른_금액으로_다시_오면_다시_차감하지_않고_처음_결과를_다시_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // when: 결과 보내기가 실패해 같은 주문을 다시 받은 경우
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 5_000L, null));

        // then: 처음과 똑같은 메시지가 두 번 나가야 받는 쪽이 같은 결과로 보고 한 번만 반영한다
        List<String> messages = messagesFor(COMPLETED_TOPIC, orderId, 2);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isEqualTo(messages.get(0));
        assertThat(messages.get(0)).contains("\"amount\":3000");
        assertThat(balanceOf(buyerId)).isEqualTo(7_000);
    }

    @Test
    void 잔액_부족으로_실패한_주문은_충전_뒤_다시_와도_차감하지_않고_실패를_다시_보낸다() {
        // given: 잔액 부족으로 실패한 뒤 충전함
        long buyerId = nextId();
        long orderId = nextId();
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));
        charger.charge(buyerId, "pay-" + orderId, 10_000);

        // when
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // then: 주문은 이미 실패로 처리됐으므로 돈이 빠지면 안 된다
        String failed = failedJson(orderId, buyerId, "INSUFFICIENT_BALANCE");
        assertThat(messagesFor(FAILED_TOPIC, orderId, 2)).containsExactly(failed, failed);
        assertThat(balanceOf(buyerId)).isEqualTo(10_000);
    }

    @Test
    void Kafka로_받기_전에_결제한_주문이_Kafka로_와도_다시_차감하지_않고_처음_결과를_보낸다() {
        // given: 배포 전 Spring 이벤트로 결제한 주문. 주문은 같은 주문을 Kafka로도 보내 왔고, 지갑의 새 그룹은 토픽을 처음부터 읽어 다시 받는다
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        buyerWalletService.payForOrder(buyerId, orderId, 3_000L, null);

        // when
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // then: 같은 처리 기록을 써서 한 번만 차감되고 처음 결과가 나간다
        assertThat(messagesFor(COMPLETED_TOPIC, orderId, 1)).singleElement().asString().contains("\"amount\":3000");
        assertThat(balanceOf(buyerId)).isEqualTo(7_000);
    }

    @Test
    void 구매자_ID가_없으면_기록하지_않고_예외가_난다() {
        // given
        long orderId = nextId();
        OrderPaymentCommand command = new OrderPaymentCommand(orderId, null, 3_000L, null);

        // when & then: 결과를 알릴 구매자를 몰라 실패 기록에 남겨 사람이 보게 한다
        assertThatThrownBy(() -> orderPaymentUseCase.payAndSendResult(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("주문 ID와 구매자 ID는 필수입니다. command=" + command);
        assertThat(countPaymentRecords(orderId)).isZero();
    }

    @Test
    void 이미_결제한_주문이_금액_0_이하로_다시_와도_차감하지_않고_처음_결과를_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 3_000L, null));

        // when: 금액이 빠진 채 같은 주문이 다시 옴
        orderPaymentUseCase.payAndSendResult(new OrderPaymentCommand(orderId, buyerId, 0L, null));

        // then: 처리할 수 없는 요청 실패가 아니라 처음 결과(결제 완료)를 다시 보낸다
        List<String> messages = messagesFor(COMPLETED_TOPIC, orderId, 2);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isEqualTo(messages.get(0));
        assertThat(balanceOf(buyerId)).isEqualTo(7_000);
    }

    @Test
    void 트랜잭션_안에서_부르면_결제하지_않고_예외가_난다() {
        // given: 트랜잭션 안에서 부르면 커밋 전에 결과를 보내게 된다
        long buyerId = nextId();
        long orderId = nextId();
        charger.charge(buyerId, "pay-" + orderId, 10_000);
        OrderPaymentCommand command = new OrderPaymentCommand(orderId, buyerId, 3_000L, null);

        // when & then
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> orderPaymentUseCase.payAndSendResult(command)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countPaymentRecords(orderId)).isZero();
        assertThat(balanceOf(buyerId)).isEqualTo(10_000);
    }

    private List<String> messagesFor(String topic, long orderId, int expectedCount) {
        return new KafkaTopicReader(consumerFactory).valuesWithKey(topic, String.valueOf(orderId), expectedCount);
    }

    private static String failedJson(long orderId, long buyerId, String reason) {
        return "{\"orderId\":" + orderId + ",\"buyerId\":" + buyerId + ",\"reason\":\"" + reason + "\"}";
    }

    private LocalDateTime recordedAt(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_at FROM wallet_order_payment WHERE order_id = ?", LocalDateTime.class, orderId);
    }

    private String orderSummaryOf(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_summary FROM wallet_order_payment WHERE order_id = ?", String.class, orderId);
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
