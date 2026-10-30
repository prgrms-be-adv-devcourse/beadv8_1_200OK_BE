package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.ok.delivery.fixture.ShipmentFixture.NOW;
import static com.ok.delivery.fixture.ShipmentFixture.address;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.otherAddress;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ShipmentAddressChangeRequestRepositoryIntegrationTest extends AbstractIntegrationTest {

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

    @Test
    void 요청_배송지와_변경_전_배송지를_각각_저장하고_조회한다() {
        // given
        Shipment shipment = shipmentRepository.saveAndFlush(shipment(item(1L)));
        ShipmentAddressChangeRequest request = requestRepository.saveAndFlush(
                shipment.requestAddressChange(otherAddress(), 3_000, NOW));

        // when
        ShipmentAddressChangeRequest sut = requestRepository.findById(request.getId()).orElseThrow();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getShipmentId()).isEqualTo(shipment.getId());
            softly.assertThat(sut.getRequestedAddress()).isEqualTo(otherAddress());
            softly.assertThat(sut.getPreviousAddress()).isEqualTo(address());
            softly.assertThat(sut.getStatus()).isEqualTo(AddressChangeStatus.PENDING);
            softly.assertThat(sut.getAdditionalFeeNotice()).isEqualTo(3_000);
            softly.assertThat(sut.getRequestedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getProcessedAt()).isNull();
        });
    }
}
