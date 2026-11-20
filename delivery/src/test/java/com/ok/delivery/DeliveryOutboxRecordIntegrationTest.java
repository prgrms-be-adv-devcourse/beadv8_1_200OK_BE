package com.ok.delivery;

import com.ok.delivery.domain.DeliveryOutboxEventType;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.delivery.adapter.in.event.CarrierDeliveredEvent;
import com.ok.delivery.fixture.ShipmentFixture;
import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
import com.ok.delivery.application.service.ShipmentService;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 리스너는 켜지 않는다. 다른 통합 테스트 컨텍스트와 같은 그룹을 쓰므로 켜면 배송 Kafka 메시지(출고지 우편번호, 결제 완료)를 이 컨텍스트가 가져갈 수 있다
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class DeliveryOutboxRecordIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ShipmentService shipmentService;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @AfterEach
    void tearDown() {
        // 테스트에 트랜잭션을 걸지 않아 롤백되지 않으므로 테스트마다 데이터를 정리한다
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
        jdbcTemplate.update("DELETE FROM delivery_outbox");
    }

    @Test
    void 출고하면_송장_정보를_담은_배송_시작_메시지를_outbox에_남긴다() {
        // given
        Long shipmentId = shipmentRepository.save(ShipmentFixture.shipmentIn(ShipmentStatus.PREPARING)).getId();

        // when
        shipmentService.dispatch(new ShipmentDispatchCommand(shipmentId, 10L, "CJ_LOGISTICS", "1234567890"));

        // then
        List<Map<String, Object>> rows = awaitOutboxOf(DeliveryOutboxEventType.SHIPMENT_DISPATCHED);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertSoftly(softly -> {
            softly.assertThat(row.get("message_key")).isEqualTo("1");
            softly.assertThat((String) row.get("payload")).contains(
                    "\"shipmentId\":" + shipmentId, "\"orderId\":1", "\"sellerId\":10",
                    "\"carrier\":\"CJ_LOGISTICS\"", "\"trackingNumber\":\"1234567890\"", "\"shippedAt\":");
        });
    }

    @Test
    void 택배사가_배송_완료를_알리면_배송_완료_메시지를_outbox에_남긴다() {
        // given
        Long shipmentId = shipmentRepository.save(ShipmentFixture.shipmentIn(ShipmentStatus.SHIPPING)).getId();

        // when
        eventPublisher.publishEvent(new CarrierDeliveredEvent(shipmentId));

        // then
        List<Map<String, Object>> rows = awaitOutboxOf(DeliveryOutboxEventType.SHIPMENT_DELIVERED);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertSoftly(softly -> {
            softly.assertThat(row.get("message_key")).isEqualTo("1");
            softly.assertThat((String) row.get("payload")).contains(
                    "\"shipmentId\":" + shipmentId, "\"orderId\":1", "\"sellerId\":10", "\"deliveredAt\":");
        });
    }

    // 즉시 발송이 Kafka로 보내고 상태를 바꿀 수 있어 상태와 상관없이 종류로만 찾는다
    private List<Map<String, Object>> awaitOutboxOf(DeliveryOutboxEventType eventType) {
        return await().atMost(Duration.ofSeconds(10)).until(
                () -> jdbcTemplate.queryForList(
                        "SELECT message_key, payload FROM delivery_outbox WHERE event_type = ?", eventType.name()),
                rows -> !rows.isEmpty());
    }
}
