package com.ok.delivery.repository;

import com.ok.config.JpaConfig;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static com.ok.delivery.fixture.ShipmentFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class ShipmentRepositoryTest {

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    void 배송지와_상품_항목을_함께_저장하고_조회한다() {
        // given
        Shipment shipment = shipmentRepository.saveAndFlush(shipment(item(1L), item(2L)));
        entityManager.clear();

        // when
        Shipment sut = shipmentRepository.findById(shipment.getId()).orElseThrow();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.PREPARING);
            softly.assertThat(sut.getAddress()).isEqualTo(address());
            softly.assertThat(sut.getRegionGroup()).isEqualTo("GENERAL");
            softly.assertThat(sut.getBaseShippingFee()).isEqualTo(3_000);
            softly.assertThat(sut.getFreeShippingThreshold()).isEqualTo(50_000);
            softly.assertThat(sut.isDelayed()).isFalse();
            softly.assertThat(sut.getItems())
                    .extracting(ShipmentItem::getOrderItemId)
                    .containsExactlyInAnyOrder(1L, 2L);
            softly.assertThat(sut.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void 송장번호가_중복되면_저장할_수_없다() {
        // given
        Shipment first = shipment(1L, 10L, item(1L));
        first.dispatch("CJ_LOGISTICS", "1234567890", NOW);
        shipmentRepository.saveAndFlush(first);
        Shipment second = shipment(2L, 10L, item(2L));
        second.dispatch("HANJIN", "1234567890", NOW);

        // when & then
        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 송장_등록_전_shipment는_여러_건_저장할_수_있다() {
        // given
        shipmentRepository.saveAndFlush(shipment(1L, 10L, item(1L)));

        // when
        shipmentRepository.saveAndFlush(shipment(2L, 10L, item(2L)));

        // then
        assertThat(shipmentRepository.count()).isEqualTo(2);
    }

    @Test
    void 같은_주문의_같은_판매자_shipment는_하나만_저장할_수_있다() {
        // given
        shipmentRepository.saveAndFlush(shipment(1L, 10L, item(1L)));

        // when & then
        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(shipment(1L, 10L, item(2L))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_주문_상품은_두_shipment에_들어갈_수_없다() {
        // given
        shipmentRepository.saveAndFlush(shipment(1L, 10L, item(1L)));

        // when & then
        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(shipment(2L, 10L, item(1L))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
