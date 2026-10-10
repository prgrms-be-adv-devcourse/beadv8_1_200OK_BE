package com.ok.delivery.repository;

import com.ok.config.JpaConfig;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import static com.ok.delivery.fixture.ShipmentFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class ShipmentAddressChangeRequestRepositoryTest {

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    ShipmentAddressChangeRequestRepository requestRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    void 요청_배송지와_변경_전_배송지를_각각_저장하고_조회한다() {
        // given
        Shipment shipment = shipmentRepository.saveAndFlush(shipment(item(1L)));
        ShipmentAddressChangeRequest request = requestRepository.saveAndFlush(
                shipment.requestAddressChange(otherAddress(), 3_000, NOW));
        entityManager.clear();

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
