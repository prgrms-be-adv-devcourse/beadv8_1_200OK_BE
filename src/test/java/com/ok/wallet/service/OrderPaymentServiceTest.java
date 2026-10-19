package com.ok.wallet.service;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.common.event.WalletPaymentFailedEvent;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.service.dto.OrderPaymentCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 서비스 → 발행 구현체 → 이벤트까지 실제 경로로 확인한다. 테스트 중 발행된 이벤트를 기록해 검사한다.
// 차감이 실제로 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않는다.
@RecordApplicationEvents
class OrderPaymentServiceTest extends AbstractIntegrationTest {
    private static final Long BUYER_ID = 40L;
    private static final Long ORDER_ID = 4_001L;

    @Autowired
    OrderPaymentService orderPaymentService;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    ApplicationEvents events;

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
    void 잔액이_충분하면_차감하고_결제_완료_이벤트를_발행한다() {
        // given
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-40", 10_000);
        events.clear();

        // when
        orderPaymentService.pay(new OrderPaymentCommand(ORDER_ID, BUYER_ID, 3_000L));

        // then
        assertThat(balanceOf(BUYER_ID)).isEqualTo(7_000);
        List<WalletPaymentCompletedEvent> completed = events.stream(WalletPaymentCompletedEvent.class).toList();
        assertThat(completed).singleElement().satisfies(event -> {
            assertThat(event.orderId()).isEqualTo(ORDER_ID);
            assertThat(event.buyerId()).isEqualTo(BUYER_ID);
            assertThat(event.amount()).isEqualTo(3_000L);
            assertThat(event.paidAt()).isNotNull();
        });
        assertThat(events.stream(WalletPaymentFailedEvent.class)).isEmpty();
    }

    @Test
    void 잔액이_부족하면_차감하지_않고_잔액_부족_실패_이벤트를_발행한다() {
        // given
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-40", 10_000);
        events.clear();

        // when
        orderPaymentService.pay(new OrderPaymentCommand(ORDER_ID, BUYER_ID, 20_000L));

        // then
        assertThat(balanceOf(BUYER_ID)).isEqualTo(10_000);
        assertThat(events.stream(WalletPaymentFailedEvent.class).toList())
                .singleElement()
                .isEqualTo(new WalletPaymentFailedEvent(ORDER_ID, BUYER_ID, "INSUFFICIENT_BALANCE"));
        assertThat(events.stream(WalletPaymentCompletedEvent.class)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1_000})
    void 결제_금액이_없거나_0_이하면_차감하지_않고_처리할_수_없는_요청_실패_이벤트를_발행한다(Long amount) {
        // given
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-40", 10_000);
        events.clear();

        // when
        orderPaymentService.pay(new OrderPaymentCommand(ORDER_ID, BUYER_ID, amount));

        // then: 주문이 결제 대기로 멈추지 않도록 실패를 알린다
        assertThat(balanceOf(BUYER_ID)).isEqualTo(10_000);
        assertThat(events.stream(WalletPaymentFailedEvent.class).toList())
                .singleElement()
                .isEqualTo(new WalletPaymentFailedEvent(ORDER_ID, BUYER_ID, "INVALID_PAYMENT_REQUEST"));
    }

    @Test
    void 이미_결제된_주문이면_다시_차감하지_않고_결과_이벤트도_다시_보내지_않는다() {
        // given: 같은 주문을 한 번 결제함
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-40", 10_000);
        orderPaymentService.pay(new OrderPaymentCommand(ORDER_ID, BUYER_ID, 3_000L));
        events.clear();

        // when: 주문 생성 이벤트가 재발행됨
        orderPaymentService.pay(new OrderPaymentCommand(ORDER_ID, BUYER_ID, 3_000L));

        // then
        assertThat(balanceOf(BUYER_ID)).isEqualTo(7_000);
        assertThat(events.stream(WalletPaymentCompletedEvent.class)).isEmpty();
        assertThat(events.stream(WalletPaymentFailedEvent.class)).isEmpty();
    }

    @Test
    void 주문_id가_없으면_차감하지_않고_이벤트도_보내지_않는다() {
        // given
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-40", 10_000);
        events.clear();

        // when: 실패를 알릴 주문이 없어 기록만 하고 끝낸다
        orderPaymentService.pay(new OrderPaymentCommand(null, BUYER_ID, 3_000L));

        // then
        assertThat(balanceOf(BUYER_ID)).isEqualTo(10_000);
        assertThat(events.stream(WalletPaymentCompletedEvent.class)).isEmpty();
        assertThat(events.stream(WalletPaymentFailedEvent.class)).isEmpty();
    }

    @Test
    void 잔액_부족으로_실패한_주문이_충전_후_다시_와도_차감하지_않고_이벤트도_보내지_않는다() {
        // given: 잔액 부족으로 실패한 뒤 충전함
        orderPaymentService.pay(new OrderPaymentCommand(ORDER_ID, BUYER_ID, 3_000L));
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-40", 10_000);
        events.clear();

        // when: 같은 주문이 재전달됨
        orderPaymentService.pay(new OrderPaymentCommand(ORDER_ID, BUYER_ID, 3_000L));

        // then
        assertThat(balanceOf(BUYER_ID)).isEqualTo(10_000);
        assertThat(events.stream(WalletPaymentCompletedEvent.class)).isEmpty();
        assertThat(events.stream(WalletPaymentFailedEvent.class)).isEmpty();
    }

    private long balanceOf(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }

    // FK 때문에 원장을 지갑보다 먼저 지운다. 이 클래스의 회원 데이터만 지운다.
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM wallet_order_payment WHERE buyer_id = ?", BUYER_ID);
        jdbcTemplate.update("""
                DELETE l FROM buyer_wallet_ledger l
                JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
                WHERE w.member_id = ?
                """, BUYER_ID);
        jdbcTemplate.update("DELETE FROM buyer_wallet WHERE member_id = ?", BUYER_ID);
    }
}
