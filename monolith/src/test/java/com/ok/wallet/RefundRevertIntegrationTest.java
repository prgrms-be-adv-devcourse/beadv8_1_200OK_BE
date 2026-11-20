package com.ok.wallet;

import com.ok.PaldoGotganApplication;
import com.ok.refund.adapter.in.event.RefundInboundTopics;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import com.ok.refund.application.port.out.WalletRevertRequest;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.adapter.out.event.WalletOutboundTopics;
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

// 환불이 보내는 되돌리기(돌려준 예치금 다시 가져오기) 요청이 실제 Kafka로 지갑까지 닿아 돈을 다시 가져오는지 확인한다 (#611)
// - 지갑 시험은 지갑에 적힌 토픽 이름으로 보내고 받아 환불과 이름이 어긋나도 통과하므로, 환불이 실제로 쓰는 보내기(WalletRefundRequestPort)로 보낸다
// - 반환은 이 연결과 상관없어 지갑 서비스로 바로 처리한다(반환 연결은 RefundReturnIntegrationTest가 본다).
//   환불 쪽에는 이 환불이 없어, 지갑이 보낸 결과를 환불이 받으면 "환불이 없어 넘김"만 남기고 상태를 바꾸지 않는다
@SpringBootTest(classes = PaldoGotganApplication.class)
class RefundRevertIntegrationTest extends AbstractIntegrationTest {
    // 이 클래스 전용 번호 (RefundReturnIntegrationTest는 610_001, 6_101~6_102 사용)
    private static final long REFUND_ID = 611_001L;
    private static final long ORDER_ID = 611_001L;
    private static final long BUYER_ID = 6_111L;

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
    void 환불이_보낸_되돌리기_요청을_지갑이_받아_돌려준_예치금을_다시_가져온다() {
        // given: 50,000원 충전, 35,000원 결제, 7,000원 반환 → 잔액 22,000원
        buyerWalletService.chargeByPayment(BUYER_ID, "pay-" + BUYER_ID + "-" + ORDER_ID, 50_000);
        buyerWalletService.payForOrder(BUYER_ID, ORDER_ID, 35_000, null);
        buyerWalletService.refundForOrder(REFUND_ID, ORDER_ID, BUYER_ID, 7_000);

        // when: 환불이 재고 거절 뒤에 부르는 것과 같은 보내기로 되돌리기를 요청한다
        walletRefundRequestPort.requestRevert(new WalletRevertRequest(REFUND_ID, ORDER_ID, BUYER_ID, 7_000));

        // then: 지갑이 받아 다시 가져오고 처리 기록을 남긴다. 실패하면 마지막으로 본 처리 기록이 메시지에 남는다
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(revertStatus()).as("지갑의 되돌리기 처리 기록(refundId=%d)", REFUND_ID).containsExactly("REVERTED"));
        assertThat(buyerWalletService.getBalance(BUYER_ID)).isEqualTo(15_000L);
    }

    @Test
    void 지갑이_보내는_되돌리기_결과_토픽은_환불이_받는_토픽과_같다() {
        // 결과 토픽 이름도 양쪽에 따로 적혀 있어, 어긋나면 지갑은 보내고 환불은 기다리기만 한다
        assertThat(WalletOutboundTopics.WALLET_REFUND_REVERTED).isEqualTo(RefundInboundTopics.WALLET_REFUND_REVERTED);
        assertThat(WalletOutboundTopics.WALLET_REFUND_REVERT_FAILED)
                .isEqualTo(RefundInboundTopics.WALLET_REFUND_REVERT_FAILED);
    }

    private List<String> revertStatus() {
        return jdbcTemplate.queryForList(
                q("SELECT status FROM wallet_order_refund_revert WHERE refund_id = ?"), String.class, REFUND_ID);
    }

    // 원장이 지갑을 외래 키로 참조하므로 원장을 먼저 지운다. 이 클래스 번호만 지운다
    private void cleanUp() {
        jdbcTemplate.update(q("DELETE FROM wallet_order_refund_revert WHERE refund_id = ?"), REFUND_ID);
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
        return sql.replaceAll("\\b(buyer_wallet(?:_ledger)?|wallet_order_(?:refund_revert|refund|payment))\\b",
                WalletSchema.NAME + ".$1");
    }
}
