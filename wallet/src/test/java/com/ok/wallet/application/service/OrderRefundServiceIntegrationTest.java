package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.dto.OrderRefundCommand;
import com.ok.wallet.application.port.in.OrderRefundUseCase;
import com.ok.wallet.support.KafkaTopicReader;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 통합 테스트: 반환을 처리한 뒤 환불이 받을 결과 메시지가 실제 Kafka에 약속한 모양(#34, 환불 PR #555)으로 나가는지 확인한다.
// 환불 코드는 쓰지 않고, 토픽에 나간 JSON을 문자열 그대로 읽어 필드 이름과 값을 본다
class OrderRefundServiceIntegrationTest extends AbstractIntegrationTest {
    private static final String COMPLETED_TOPIC = "wallet.wallet-refund-completed";
    private static final String FAILED_TOPIC = "wallet.wallet-refund-failed";

    // 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 주문·환불 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(8_000_000L);

    @Autowired
    OrderRefundUseCase orderRefundUseCase;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    @BeforeEach
    void setUp() {
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
    }

    @AfterEach
    void tearDown() {
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 돌려주면_완료_메시지를_주문_번호_키로_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();
        paidOrder(buyerId, orderId, 35_000);

        // when
        orderRefundUseCase.refund(new OrderRefundCommand(refundId, orderId, buyerId, 7_000L));

        // then
        assertThat(messagesFor(COMPLETED_TOPIC, orderId, 1))
                .containsExactly("{\"refundId\":" + refundId + ",\"orderId\":" + orderId
                        + ",\"buyerId\":" + buyerId + ",\"amount\":7000}");
    }

    @Test
    void 거절하면_요청_금액과_사유를_담아_실패_메시지를_보낸다() {
        // given: 결제 기록이 없는 주문
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();

        // when
        orderRefundUseCase.refund(new OrderRefundCommand(refundId, orderId, buyerId, 7_000L));

        // then
        assertThat(messagesFor(FAILED_TOPIC, orderId, 1))
                .containsExactly("{\"refundId\":" + refundId + ",\"orderId\":" + orderId
                        + ",\"buyerId\":" + buyerId + ",\"amount\":7000,\"reason\":\"PAYMENT_NOT_FOUND\"}");
    }

    @Test
    void 같은_환불이_다른_금액으로_다시_오면_돈은_주지_않고_처음_결과를_다시_보낸다() {
        // given
        long buyerId = nextId();
        long orderId = nextId();
        long refundId = nextId();
        paidOrder(buyerId, orderId, 35_000);
        orderRefundUseCase.refund(new OrderRefundCommand(refundId, orderId, buyerId, 7_000L));

        // when
        orderRefundUseCase.refund(new OrderRefundCommand(refundId, orderId, buyerId, 9_000L));

        // then: 처음 금액(7,000원)이 두 번 나가야 환불이 다른 금액을 알아챈다
        String first = "{\"refundId\":" + refundId + ",\"orderId\":" + orderId
                + ",\"buyerId\":" + buyerId + ",\"amount\":7000}";
        assertThat(messagesFor(COMPLETED_TOPIC, orderId, 2)).containsExactly(first, first);
        assertThat(balanceOf(buyerId)).isEqualTo(22_000);
    }

    @Test
    void 금액이_없으면_기록하지_않고_예외가_난다() {
        // given
        OrderRefundCommand command = new OrderRefundCommand(1L, 2L, 3L, null);

        // when & then
        assertThatThrownBy(() -> orderRefundUseCase.refund(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("반환 금액은 필수입니다. command=" + command);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_order_refund", Long.class)).isZero();
    }

    // 50,000원을 충전하고 주문을 결제한다
    private void paidOrder(long buyerId, long orderId, long amount) {
        buyerWalletService.chargeByPayment(buyerId, "pay-" + orderId, 50_000);
        buyerWalletService.payForOrder(buyerId, orderId, amount);
    }

    private List<String> messagesFor(String topic, long orderId, int expectedCount) {
        return new KafkaTopicReader(consumerFactory).valuesWithKey(topic, String.valueOf(orderId), expectedCount);
    }

    private long balanceOf(long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }

    private static long nextId() {
        return ID_SEQ.incrementAndGet();
    }
}
