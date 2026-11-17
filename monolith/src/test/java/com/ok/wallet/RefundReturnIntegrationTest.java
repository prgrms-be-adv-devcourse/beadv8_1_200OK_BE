package com.ok.wallet;

import com.ok.PaldoGotganApplication;
import com.ok.refund.application.port.out.WalletRefundRequest;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.service.BuyerWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 환불이 보내는 예치금 반환 요청이 실제 Kafka로 지갑까지 닿아 구매자에게 돈이 돌아가는지 확인한다 (#669)
// - 받는 토픽 이름은 지갑(WalletKafkaTopics)과 환불(RefundTopics)에 따로 적혀 있다. 지갑 시험은 지갑 이름으로 보내고 받아
//   두 이름이 어긋나도 통과하므로, 환불이 실제로 쓰는 보내기(WalletRefundRequestPort)로 보내 두 이름이 맞는지 본다
// - 판매자 승인 같은 환불 앞 단계는 이 연결과 상관없어 거치지 않는다. 그래서 환불 쪽에는 이 환불이 없고,
//   지갑이 보낸 결과를 환불이 받으면 "환불이 없어 넘김" 로그만 남기고 상태를 바꾸지 않는다
@SpringBootTest(classes = PaldoGotganApplication.class)
class RefundReturnIntegrationTest extends AbstractIntegrationTest {
    // 이 클래스 전용 번호 (PayoutDepositIntegrationTest는 600_001, 6_001~6_002 사용)
    private static final long REFUND_ID = 610_001L;
    private static final long ORDER_ID = 610_001L;
    private static final long BUYER_ID = 6_101L;
    private static final long SELLER_ID = 6_102L;

    @Autowired
    WalletRefundRequestPort walletRefundRequestPort;

    @Autowired
    BuyerWalletService buyerWalletService;

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
    void 환불이_보낸_반환_요청을_지갑이_받아_구매자에게_돌려준다() {
        // given: 구매자가 50,000원을 충전하고 이 주문에 35,000원을 결제했다
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-" + BUYER_ID + "-" + ORDER_ID, 50_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID, 35_000);

        // when: 환불이 판매자 승인 뒤에 부르는 것과 같은 보내기로 7,000원 반환을 요청한다
        walletRefundRequestPort.requestRefund(new WalletRefundRequest(REFUND_ID, ORDER_ID, BUYER_ID, SELLER_ID, 7_000));

        // then: 지갑이 받아 돌려주고 처리 기록을 남긴다. 실패하면 마지막으로 본 처리 기록이 메시지에 남는다
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(refundStatus()).as("지갑의 반환 처리 기록(refundId=%d)", REFUND_ID).containsExactly("COMPLETED"));
        assertThat(buyerWalletService.getBalance(BUYER_ID)).isEqualTo(22_000L);
    }

    private List<String> refundStatus() {
        return jdbcTemplate.queryForList(
                q("SELECT status FROM wallet_order_refund WHERE refund_id = ?"), String.class, REFUND_ID);
    }

    // 원장이 지갑을 외래 키로 참조하므로 원장을 먼저 지운다. 이 클래스 번호만 지운다
    private void cleanUp() {
        jdbcTemplate.update(q("DELETE FROM wallet_order_refund WHERE refund_id = ?"), REFUND_ID);
        jdbcTemplate.update(q("DELETE FROM wallet_order_payment WHERE order_id = ?"), ORDER_ID);
        jdbcTemplate.update(q("""
                DELETE l FROM buyer_wallet_ledger l
                JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
                WHERE w.member_id = ?
                """), BUYER_ID);
        jdbcTemplate.update(q("DELETE FROM buyer_wallet WHERE member_id = ?"), BUYER_ID);
    }

    // 접속 기본 DB는 기본 스키마라서 지갑 테이블 이름을 지갑 스키마로 한정한다. 쓰는 테이블이 늘면 정규식에도 더한다
    private static String q(String sql) {
        return sql.replaceAll("\\b(buyer_wallet(?:_ledger)?|wallet_order_(?:refund|payment))\\b", WalletSchema.NAME + ".$1");
    }
}
