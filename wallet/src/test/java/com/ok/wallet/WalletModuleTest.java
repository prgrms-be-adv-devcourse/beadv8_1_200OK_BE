package com.ok.wallet;

import com.ok.common.event.OrderCreatedEvent;
import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.common.event.WalletPaymentFailedEvent;
import com.ok.common.config.JpaConfig;
import com.ok.testsupport.TestClockConfiguration;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.application.port.out.BuyerWalletLedgerQueryRepository;
import com.ok.wallet.application.service.BuyerWalletService;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
// 지갑 서비스가 Clock을 주입받으므로 Clock 빈이 필요하다.
// 운영 ClockConfig(com.ok.common.config)는 다른 모듈이라 모듈 테스트에서 걸러지므로, 테스트용 TestClockConfiguration을 불러온다. (#93, 자세한 이유는 TestClockConfiguration 참고)
@Import({JpaConfig.class, TestcontainersConfiguration.class, TestClockConfiguration.class})
class WalletModuleTest {
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CompletedEventPublications completedEventPublications;

    @Autowired
    BuyerWalletService buyerWalletService;

    // 모듈 테스트는 지갑 밖의 설정(QueryDslConfig)을 걸러 내 JPAQueryFactory가 없다.
    // 이 테스트는 주문 결제 이벤트 흐름만 확인하고 이용 내역 조회는 쓰지 않으므로 가짜로 둔다.
    @MockitoBean
    BuyerWalletLedgerQueryRepository buyerWalletLedgerQueryRepository;

    // @ApplicationModuleTest는 롤백되지 않으므로 테스트마다 데이터를 정리한다
    @AfterEach
    void tearDown() {
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 주문_생성_이벤트를_받으면_예치금을_차감하고_결제_완료_이벤트를_발행한다(Scenario scenario) {
        // Given: 잔액 10,000원
        Long buyerId = 50L;
        Long orderId = 9_300_001L;
        buyerWalletService.chargeByPayment(buyerId, "module-50", 10_000);

        // When & Then: 주문 생성 → 지갑 리스너가 차감 → 결제 완료 이벤트
        // 결제 완료 이벤트는 차감과 같은 트랜잭션 안에서 발행되어, 이벤트가 도착한 순간에는 차감이 아직 커밋되지 않았을 수 있다.
        // 그때 잔액을 읽으면 차감 전 값(10,000원)이 보여 가끔 실패했으므로, 주문 생성 이벤트의 처리 완료(커밋 뒤 기록)를 기다린 뒤 확인한다
        scenario.publish(new OrderCreatedEvent(orderId, buyerId, 3_000L))
                .andWaitForStateChange(() -> countCompletedOrderCreatedEvents(orderId), count -> count == 1)
                .andVerifyEvents(events -> {
                    assertThat(events)
                            .contains(WalletPaymentCompletedEvent.class)
                            .matching(event -> event.orderId().equals(orderId))
                            .matching(event -> event.buyerId().equals(buyerId))
                            .matching(event -> event.amount().equals(3_000L));
                    assertThat(balanceOf(buyerId)).isEqualTo(7_000);
                });
    }

    // 해당 주문에 대한 OrderCreatedEvent 중 리스너 처리가 완료된 건수
    private long countCompletedOrderCreatedEvents(Long orderId) {
        return completedEventPublications.findAll().stream()
                .map(EventPublication::getEvent)
                .filter(e -> e instanceof OrderCreatedEvent created
                        && orderId.equals(created.orderId()))
                .count();
    }

    @Test
    void 잔액이_부족한_주문이면_차감하지_않고_결제_실패_이벤트를_발행한다(Scenario scenario) {
        // Given: 잔액 10,000원
        Long buyerId = 51L;
        Long orderId = 9_300_002L;
        buyerWalletService.chargeByPayment(buyerId, "module-51", 10_000);

        // When & Then
        scenario.publish(new OrderCreatedEvent(orderId, buyerId, 20_000L))
                .andWaitForEventOfType(WalletPaymentFailedEvent.class)
                .matching(event -> event.orderId().equals(orderId))
                .toArriveAndVerify(event -> {
                    assertThat(event.reason()).isEqualTo("INSUFFICIENT_BALANCE");
                    assertThat(balanceOf(buyerId)).isEqualTo(10_000);
                });
    }

    private long balanceOf(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }
}
