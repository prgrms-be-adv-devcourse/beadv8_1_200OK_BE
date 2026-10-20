package com.ok.delivery.service;

import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.delivery.repository.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.otherAddressCommand;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;

class ShipmentAddressChangeServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ShipmentAddressChangeService shipmentAddressChangeService;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    ShipmentAddressChangeRequestRepository requestRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM shipment_address_change_request");
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    // 63100(JEJU_MAIN, 3,000원)은 마이그레이션 시드 값이다. 시드가 바뀌면 기대값도 함께 바꾼다
    @Test
    void 일반_지역에서_제주로_바꾸면_제주_추가_운임을_안내_금액으로_저장한다() {
        // given
        Long shipmentId = shipmentRepository.save(shipment(item(1L))).getId();

        // when
        Long requestId = shipmentAddressChangeService.request(shipmentId, otherAddressCommand()).getId();

        // then
        ShipmentAddressChangeRequest sut = requestRepository.findById(requestId).orElseThrow();
        assertThat(sut.getAdditionalFeeNotice()).isEqualTo(3_000);
    }
}
