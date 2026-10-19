package com.ok.delivery;

import com.ok.common.event.OrderPaidEvent;
import com.ok.common.event.payload.PaidOrderItem;
import com.ok.common.event.payload.PaidSellerOrder;
import com.ok.common.event.payload.ShippingAddress;
import com.ok.config.JpaConfig;
import com.ok.testsupport.TestClockConfiguration;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
// 지역 판정 서비스가 Clock을 주입받으므로 테스트용 Clock을 불러온다 (이유는 TestClockConfiguration 참고)
@Import({JpaConfig.class, TestcontainersConfiguration.class, TestClockConfiguration.class})
class DeliveryModuleTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CompletedEventPublications completedEventPublications;

    @AfterEach
    void tearDown() {
        // @ApplicationModuleTest는 롤백되지 않으므로 테스트마다 데이터를 정리한다
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 결제_완료_이벤트를_받으면_판매자별_shipment를_만든다(Scenario scenario) {
        // given
        Long orderId = 9_400_001L;

        // when & then
        scenario.publish(orderPaidEvent(orderId, 1L, 2L))
                .andWaitForStateChange(() -> countShipments(orderId), count -> count == 2)
                .andVerify(count -> assertThat(count).isEqualTo(2));
    }

    @Test
    void 같은_결제_완료_이벤트를_두_번_받아도_판매자별_shipment는_한_건이다(Scenario scenario) {
        // given
        Long orderId = 9_400_002L;
        OrderPaidEvent event = orderPaidEvent(orderId, 3L, 4L);
        scenario.publish(event)
                .andWaitForStateChange(() -> countCompletedEvents(orderId), count -> count == 1);

        // when
        scenario.publish(event)
                .andWaitForStateChange(() -> countCompletedEvents(orderId), count -> count == 2);

        // then
        assertThat(countShipments(orderId)).isEqualTo(2);
    }

    private static OrderPaidEvent orderPaidEvent(Long orderId, Long firstOrderItemId, Long secondOrderItemId) {
        ShippingAddress address = new ShippingAddress("홍길동", "010-1234-5678", "06236", "주소", "상세 주소", null);
        return new OrderPaidEvent(orderId, LocalDateTime.of(2026, 10, 19, 10, 0), address, List.of(
                new PaidSellerOrder(10L, 3_000L, 50_000L, 0L,
                        List.of(new PaidOrderItem(orderId * 10 + firstOrderItemId, "상품", null, 1, 10_000L))),
                new PaidSellerOrder(20L, 3_000L, 50_000L, 0L,
                        List.of(new PaidOrderItem(orderId * 10 + secondOrderItemId, "상품", "옵션", 2, 5_000L)))));
    }

    private long countShipments(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shipment WHERE order_id = ?", Long.class, orderId);
    }

    // 해당 주문의 OrderPaidEvent 중 리스너 처리가 완료된 건수
    // 완료된 이벤트 발행 기록(event_publication)이 남아 있다는 전제에 의존한다
    private long countCompletedEvents(Long orderId) {
        return completedEventPublications.findAll().stream()
                .map(EventPublication::getEvent)
                .filter(e -> e instanceof OrderPaidEvent paid && paid.orderId().equals(orderId))
                .count();
    }
}
