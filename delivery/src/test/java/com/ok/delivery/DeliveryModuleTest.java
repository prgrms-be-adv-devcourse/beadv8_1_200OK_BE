package com.ok.delivery;

import com.ok.common.event.OrderPaidEvent;
import com.ok.common.event.ShipmentDeliveredEvent;
import com.ok.common.event.ShipmentDispatchedEvent;
import com.ok.common.event.payload.PaidOrderItem;
import com.ok.common.event.payload.PaidSellerOrder;
import com.ok.common.event.payload.ShippingAddress;
import com.ok.common.config.AsyncExecutorFactory;
import com.ok.common.config.AsyncProperties;
import com.ok.common.config.JpaConfig;
import com.ok.common.config.MdcTaskDecorator;
import com.ok.delivery.config.DeliveryAsyncConfig;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.delivery.adapter.in.event.CarrierDeliveredEvent;
import com.ok.delivery.fixture.ShipmentFixture;
import com.ok.delivery.application.port.out.ShipmentQueryRepository;
import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
import com.ok.delivery.application.service.ShipmentService;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.testsupport.FakeCarrierClient;
import com.ok.testsupport.KafkaTestcontainersConfiguration;
import com.ok.testsupport.TestClockConfiguration;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@ApplicationModuleTest
// 지역 판정 서비스가 Clock을 주입받으므로 테스트용 Clock을 불러온다 (이유는 TestClockConfiguration 참고)
// 통합 테스트와 같이 test 프로필로 띄워 가짜 택배사(MockCarrierClient)를 빼고, 아무 일도 하지 않는 FakeCarrierClient를 쓴다
@ActiveProfiles({"test", DeliveryModuleTest.MODULE_TEST_PROFILE})
// 배송 Kafka 설정(토픽 선언, 리스너)이 함께 뜨므로 테스트용 브로커에 붙인다. 붙이지 않으면 내 PC의 Kafka(localhost)로 붙으려 한다.
// 리스너는 켜지 않는다. 통합 테스트 컨텍스트와 같은 그룹을 쓰므로 켜면 배송 Kafka 메시지(출고지 우편번호, 결제 완료)를 이 컨텍스트가 가져갈 수 있다
@Import({JpaConfig.class, DeliveryModuleTest.AsyncExecutorFactoryConfiguration.class, TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class, TestClockConfiguration.class,
        FakeCarrierClient.class})
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class DeliveryModuleTest {

    static final String MODULE_TEST_PROFILE = "delivery-module-test";

    // DeliveryAsyncConfig가 쓰는 공용 팩토리를 만든다. 팩토리를 등록하는 AsyncConfig는 다른 모듈이라 모듈 테스트에서 걸러진다.
    // 통합 테스트의 컴포넌트 스캔(com.ok)에도 잡혀 팩토리가 둘이 되지 않게 이 테스트 프로필에서만 등록한다
    @TestConfiguration(proxyBeanMethods = false)
    @Profile(MODULE_TEST_PROFILE)
    static class AsyncExecutorFactoryConfiguration {

        @Bean
        AsyncExecutorFactory asyncExecutorFactory() {
            AsyncProperties.ExecutorProperties outboxSend = new AsyncProperties.ExecutorProperties(
                    AsyncProperties.ExecutorType.PLATFORM, 1, 2, 1000, Duration.ofSeconds(10), null);
            return new AsyncExecutorFactory(
                    new AsyncProperties(AsyncProperties.EVENT_EXECUTOR, Map.of(DeliveryAsyncConfig.OUTBOX_SEND_EXECUTOR, outboxSend)),
                    new MdcTaskDecorator());
        }
    }

    // 모듈 테스트는 배송 밖의 설정(QueryDslConfig)을 걸러 내 JPAQueryFactory가 없다.
    // 이 테스트는 이벤트 수신·발행 흐름만 확인하고 배송 목록 조회는 쓰지 않으므로 가짜로 둔다
    @MockitoBean
    ShipmentQueryRepository shipmentQueryRepository;

    @Autowired
    ShipmentService shipmentService;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CompletedEventPublications completedEventPublications;

    @AfterEach
    void tearDown() {
        // @ApplicationModuleTest는 롤백되지 않으므로 테스트마다 데이터를 정리한다
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
        jdbcTemplate.update("DELETE FROM delivery_outbox");
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
    void 무료배송_기준이_없는_판매자의_결제_완료_이벤트도_처리해_부과_배송비를_저장한다(Scenario scenario) {
        // given
        Long orderId = 9_400_003L;
        OrderPaidEvent event = new OrderPaidEvent(orderId, 1L, LocalDateTime.of(2026, 10, 19, 10, 0),
                new ShippingAddress("홍길동", "010-1234-5678", "06236", "주소", "상세 주소", null),
                List.of(new PaidSellerOrder(10L, 3_000L, null, 0L,
                        List.of(new PaidOrderItem(orderId * 10 + 1, "상품", null, 1, 10_000_000L)))));

        // when
        scenario.publish(event)
                .andWaitForStateChange(() -> countShipments(orderId), count -> count == 1);

        // then
        Integer chargedShippingFee = jdbcTemplate.queryForObject(
                "SELECT charged_shipping_fee FROM shipment WHERE order_id = ?", Integer.class, orderId);
        assertThat(chargedShippingFee).isEqualTo(3_000);
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
    void 택배사가_배송_완료를_알리면_배송_완료_이벤트를_발행한다(Scenario scenario) {
        // given
        Long shipmentId = shipmentRepository.save(ShipmentFixture.shipmentIn(ShipmentStatus.SHIPPING)).getId();

        // when & then
        scenario.publish(new CarrierDeliveredEvent(shipmentId))
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
