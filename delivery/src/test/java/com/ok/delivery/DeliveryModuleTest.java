package com.ok.delivery;

import com.ok.common.config.AsyncExecutorFactory;
import com.ok.common.config.AsyncProperties;
import com.ok.common.config.JpaConfig;
import com.ok.common.config.MdcTaskDecorator;
import com.ok.delivery.config.DeliveryAsyncConfig;
import com.ok.delivery.domain.DeliveryOutboxEventType;
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
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
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
    // 이 테스트는 출고·배송 완료 발행 흐름만 확인하고 배송 목록 조회는 쓰지 않으므로 가짜로 둔다
    @MockitoBean
    ShipmentQueryRepository shipmentQueryRepository;

    @Autowired
    ShipmentService shipmentService;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // @ApplicationModuleTest는 롤백되지 않으므로 테스트마다 데이터를 정리한다
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
        jdbcTemplate.update("DELETE FROM delivery_outbox");
    }

    @Test
    void 출고하면_송장_정보를_담은_배송_시작_메시지를_outbox에_남긴다(Scenario scenario) {
        // given
        Long shipmentId = shipmentRepository.save(ShipmentFixture.shipmentIn(ShipmentStatus.PREPARING)).getId();

        // when & then
        scenario.stimulate(() -> shipmentService.dispatch(new ShipmentDispatchCommand(shipmentId, 10L, "CJ_LOGISTICS", "1234567890")))
                .andWaitForStateChange(() -> outboxOf(DeliveryOutboxEventType.SHIPMENT_DISPATCHED), rows -> !rows.isEmpty())
                .andVerify(rows -> {
                    assertThat(rows).hasSize(1);
                    Map<String, Object> row = rows.getFirst();
                    assertSoftly(softly -> {
                        softly.assertThat(row.get("message_key")).isEqualTo("1");
                        softly.assertThat((String) row.get("payload")).contains(
                                "\"shipmentId\":" + shipmentId, "\"orderId\":1", "\"sellerId\":10",
                                "\"carrier\":\"CJ_LOGISTICS\"", "\"trackingNumber\":\"1234567890\"", "\"shippedAt\":");
                    });
                });
    }

    @Test
    void 택배사가_배송_완료를_알리면_배송_완료_메시지를_outbox에_남긴다(Scenario scenario) {
        // given
        Long shipmentId = shipmentRepository.save(ShipmentFixture.shipmentIn(ShipmentStatus.SHIPPING)).getId();

        // when & then
        scenario.publish(new CarrierDeliveredEvent(shipmentId))
                .andWaitForStateChange(() -> outboxOf(DeliveryOutboxEventType.SHIPMENT_DELIVERED), rows -> !rows.isEmpty())
                .andVerify(rows -> {
                    assertThat(rows).hasSize(1);
                    Map<String, Object> row = rows.getFirst();
                    assertSoftly(softly -> {
                        softly.assertThat(row.get("message_key")).isEqualTo("1");
                        softly.assertThat((String) row.get("payload")).contains(
                                "\"shipmentId\":" + shipmentId, "\"orderId\":1", "\"sellerId\":10", "\"deliveredAt\":");
                    });
                });
    }

    // 즉시 발송이 Kafka로 보내고 상태를 바꿀 수 있어 상태와 상관없이 종류로만 찾는다
    private List<Map<String, Object>> outboxOf(DeliveryOutboxEventType eventType) {
        return jdbcTemplate.queryForList(
                "SELECT message_key, payload FROM delivery_outbox WHERE event_type = ?", eventType.name());
    }
}
