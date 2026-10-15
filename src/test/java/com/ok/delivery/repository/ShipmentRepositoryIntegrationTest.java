package com.ok.delivery.repository;

import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static com.ok.delivery.fixture.ShipmentFixture.NOW;
import static com.ok.delivery.fixture.ShipmentFixture.address;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ShipmentRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 배송지와_상품_항목을_함께_저장하고_조회한다() {
        // given
        Long shipmentId = shipmentRepository.saveAndFlush(shipment(item(1L), item(2L))).getId();

        // when
        // items는 지연 로딩이라 트랜잭션 안에서 함께 읽는다
        Shipment sut = transactionTemplate.execute(status -> {
            Shipment found = shipmentRepository.findById(shipmentId).orElseThrow();
            found.getItems().size();
            return found;
        });

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
    void 같은_택배사의_송장번호가_중복되면_저장할_수_없다() {
        // given
        Shipment first = shipment(1L, 10L, item(1L));
        first.dispatch("CJ_LOGISTICS", "1234567890", NOW);
        shipmentRepository.saveAndFlush(first);
        Shipment second = shipment(2L, 10L, item(2L));
        second.dispatch("CJ_LOGISTICS", "1234567890", NOW);

        // when & then
        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_shipment_carrier_tracking_number");
    }

    @Test
    void 택배사가_다르면_같은_송장번호도_저장할_수_있다() {
        // given
        Shipment first = shipment(1L, 10L, item(1L));
        first.dispatch("CJ_LOGISTICS", "1234567890", NOW);
        shipmentRepository.saveAndFlush(first);
        Shipment second = shipment(2L, 10L, item(2L));
        second.dispatch("HANJIN", "1234567890", NOW);

        // when
        Shipment sut = shipmentRepository.saveAndFlush(second);

        // then
        assertThat(sut.getId()).isNotNull();
    }

    @Test
    void 송장_등록_전_shipment는_여러_건_저장할_수_있다() {
        // given
        shipmentRepository.saveAndFlush(shipment(1L, 10L, item(1L)));

        // when
        Shipment sut = shipmentRepository.saveAndFlush(shipment(2L, 10L, item(2L)));

        // then
        assertThat(sut.getId()).isNotNull();
    }

    @Test
    void 같은_주문의_같은_판매자_shipment는_하나만_저장할_수_있다() {
        // given
        shipmentRepository.saveAndFlush(shipment(1L, 10L, item(1L)));

        // when & then
        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(shipment(1L, 10L, item(2L))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_shipment_order_seller");
    }

    @Test
    void 같은_주문_상품은_두_shipment에_들어갈_수_없다() {
        // given
        shipmentRepository.saveAndFlush(shipment(1L, 10L, item(1L)));

        // when & then
        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(shipment(2L, 10L, item(1L))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_shipment_item_order_item_id");
    }
}
