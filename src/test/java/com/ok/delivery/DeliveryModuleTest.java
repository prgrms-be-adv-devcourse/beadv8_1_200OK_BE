package com.ok.delivery;

import com.ok.common.event.OrderPaidEvent;
import com.ok.common.event.ShipmentDeliveredEvent;
import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.common.event.payload.PaidOrderItem;
import com.ok.common.event.payload.PaidSellerOrder;
import com.ok.common.event.payload.ShippingAddress;
import com.ok.config.JpaConfig;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.delivery.fixture.ShipmentFixture;
import com.ok.delivery.repository.ShipmentQueryRepository;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.ShipmentService;
import com.ok.delivery.service.dto.ShipmentDispatchCommand;
import com.ok.product.SellerShippingPolicyProvider;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@ApplicationModuleTest
// 지역 판정 서비스가 Clock을 주입받으므로 테스트용 Clock을 불러온다 (이유는 TestClockConfiguration 참고)
@Import({JpaConfig.class, TestcontainersConfiguration.class, TestClockConfiguration.class})
class DeliveryModuleTest {

    // 판매자 배송 설정은 상품 모듈 빈이라 모듈 테스트에 뜨지 않는다. Modulith는 다른 모듈 타입의 빈 정의를 @Import로 넣어도 걸러 내므로 mock으로 채운다.
    // mock은 빈 Map을 돌려줘 모든 판매자가 기본값으로 계산된다. 상품 쪽 실제 구현도 저장소가 생기기 전까지 같은 동작이다
    @MockitoBean
    SellerShippingPolicyProvider sellerShippingPolicyProvider;

    // 모듈 테스트는 배송 밖의 설정(QueryDslConfig)을 걸러 내 JPAQueryFactory가 없다.
    // 이 테스트는 이벤트 수신·발행 흐름만 확인하고 배송 목록 조회는 쓰지 않으므로 가짜로 둔다
    @MockitoBean
    ShipmentQueryRepository shipmentQueryRepository;

    @Autowired
    ShipmentService shipmentService;

    @Autowired
    ShipmentRepository shipmentRepository;

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

    @Test
    void 출고하면_송장_정보를_담은_배송_시작_이벤트를_발행한다(Scenario scenario) {
        // given
        Long shipmentId = shipmentRepository.save(ShipmentFixture.shipmentIn(ShipmentStatus.PREPARING)).getId();

        // when & then
        scenario.stimulate(() -> shipmentService.dispatch(new ShipmentDispatchCommand(shipmentId, 10L, "CJ_LOGISTICS", "1234567890")))
                .andWaitForEventOfType(ShipmentDispatchedEvent.class)
                .matching(event -> event.shipmentId().equals(shipmentId))
                .toArriveAndVerify(event -> {
                    assertThat(event).isNotNull();
                    assertSoftly(softly -> {
                        softly.assertThat(event.orderId()).isEqualTo(1L);
                        softly.assertThat(event.sellerId()).isEqualTo(10L);
                        softly.assertThat(event.carrier()).isEqualTo("CJ_LOGISTICS");
                        softly.assertThat(event.trackingNumber()).isEqualTo("1234567890");
                        softly.assertThat(event.shippedAt()).isNotNull();
                    });
                });
    }

    @Test
    void 배송_완료를_반영하면_배송_완료_이벤트를_발행한다(Scenario scenario) {
        // given
        Long shipmentId = shipmentRepository.save(ShipmentFixture.shipmentIn(ShipmentStatus.SHIPPING)).getId();

        // when & then
        scenario.stimulate(() -> shipmentService.deliver(shipmentId))
                .andWaitForEventOfType(ShipmentDeliveredEvent.class)
                .matching(event -> event.shipmentId().equals(shipmentId))
                .toArriveAndVerify(event -> {
                    assertThat(event).isNotNull();
                    assertSoftly(softly -> {
                        softly.assertThat(event.orderId()).isEqualTo(1L);
                        softly.assertThat(event.sellerId()).isEqualTo(10L);
                        softly.assertThat(event.deliveredAt()).isNotNull();
                    });
                });
    }

    private static OrderPaidEvent orderPaidEvent(Long orderId, Long firstOrderItemId, Long secondOrderItemId) {
        ShippingAddress address = new ShippingAddress("홍길동", "010-1234-5678", "06236", "주소", "상세 주소", null);
        return new OrderPaidEvent(orderId, 1L, LocalDateTime.of(2026, 10, 19, 10, 0), address, List.of(
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
