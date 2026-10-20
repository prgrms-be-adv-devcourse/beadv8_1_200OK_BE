package com.ok.delivery.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.repository.RemoteAreaPostalCodeRepository;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.dto.AddressCommand;
import com.ok.delivery.service.dto.SellerShipmentCommand;
import com.ok.delivery.service.dto.ShipmentCreateCommand;
import com.ok.delivery.service.dto.ShipmentItemCommand;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ShipmentServiceIntegrationTest extends AbstractIntegrationTest {

    // 2026-10-19는 월요일. 마감(13:00) 전이라 당일 출고된다
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 10, 0);
    private static final String GENERAL_POSTAL_CODE = "06236";
    private static final String JEJU_MAIN_POSTAL_CODE = "63100";
    // 초기 데이터에 없는 번호라 테스트 행이 시드와 겹치지 않는다
    private static final String TEST_POSTAL_CODE = "99999";

    @Autowired
    ShipmentService shipmentService;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    RemoteAreaPostalCodeRepository postalCodeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
        jdbcTemplate.update("DELETE FROM remote_area_postal_code WHERE postal_code = ?", TEST_POSTAL_CODE);
    }

    @Test
    void 판매자가_한_명이면_shipment_한_건에_결제_시점_값을_저장한다() {
        // given
        ShipmentCreateCommand command = command(1L, PAID_AT, GENERAL_POSTAL_CODE, seller(10L, 0L, 101L, 102L));

        // when
        shipmentService.create(command);

        // then
        List<Shipment> shipments = shipmentsOf(1L);
        assertThat(shipments).hasSize(1);
        Shipment sut = shipments.get(0);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getBuyerId()).isEqualTo(100L);
            softly.assertThat(sut.getSellerId()).isEqualTo(10L);
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.PREPARING);
            softly.assertThat(sut.getAddress().getRecipientPhone()).isEqualTo("01012345678");
            softly.assertThat(sut.getAddress().getPostalCode()).isEqualTo(GENERAL_POSTAL_CODE);
            softly.assertThat(sut.getRegionGroup()).isEqualTo("GENERAL");
            softly.assertThat(sut.getBaseShippingFee()).isEqualTo(3_000);
            softly.assertThat(sut.getFreeShippingThreshold()).isEqualTo(50_000);
            softly.assertThat(sut.getRegionSurcharge()).isZero();
            // 월요일 출고 + 2~3영업일
            softly.assertThat(sut.getEstimatedDeliveryStart()).isEqualTo(LocalDate.of(2026, 10, 21));
            softly.assertThat(sut.getEstimatedDeliveryEnd()).isEqualTo(LocalDate.of(2026, 10, 22));
            softly.assertThat(orderItemIdsOf(sut)).containsExactly(101L, 102L);
        });
    }

    @Test
    void 판매자가_두_명이면_판매자별로_shipment를_만든다() {
        // given
        ShipmentCreateCommand command = command(2L, PAID_AT, GENERAL_POSTAL_CODE,
                seller(10L, 0L, 201L), seller(20L, 0L, 202L, 203L));

        // when
        shipmentService.create(command);

        // then
        List<Shipment> sut = shipmentsOf(2L);
        assertThat(sut).hasSize(2);
        assertSoftly(softly -> {
            softly.assertThat(sut.get(0).getSellerId()).isEqualTo(10L);
            softly.assertThat(orderItemIdsOf(sut.get(0))).containsExactly(201L);
            softly.assertThat(sut.get(1).getSellerId()).isEqualTo(20L);
            softly.assertThat(orderItemIdsOf(sut.get(1))).containsExactly(202L, 203L);
        });
    }

    @Test
    void 도서산간_주소면_지역_그룹과_추가_운임과_늘어난_도착_예정일을_저장한다() {
        // given: 제주 본섬은 추가 운임 3,000원, 추가 소요일 1일
        ShipmentCreateCommand command = command(3L, PAID_AT, JEJU_MAIN_POSTAL_CODE, seller(10L, 3_000L, 301L));

        // when
        shipmentService.create(command);

        // then
        Shipment sut = shipmentsOf(3L).get(0);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getRegionGroup()).isEqualTo("JEJU_MAIN");
            softly.assertThat(sut.getRegionSurcharge()).isEqualTo(3_000);
            softly.assertThat(sut.getEstimatedDeliveryStart()).isEqualTo(LocalDate.of(2026, 10, 22));
            softly.assertThat(sut.getEstimatedDeliveryEnd()).isEqualTo(LocalDate.of(2026, 10, 23));
        });
    }

    @Test
    void 우편번호_데이터가_바뀌어도_이미_만든_shipment_값은_그대로다() {
        // given: 울릉 그룹(5,000원, 2일)으로 등록한 번호로 shipment를 만든 뒤 번호를 삭제한다
        postalCodeRepository.saveAndFlush(RemoteAreaPostalCode.of(TEST_POSTAL_CODE, "ULLEUNG", 2));
        shipmentService.create(command(4L, PAID_AT, TEST_POSTAL_CODE, seller(10L, 5_000L, 401L)));
        jdbcTemplate.update("DELETE FROM remote_area_postal_code WHERE postal_code = ?", TEST_POSTAL_CODE);

        // when
        Shipment sut = shipmentsOf(4L).get(0);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getRegionGroup()).isEqualTo("ULLEUNG");
            softly.assertThat(sut.getRegionSurcharge()).isEqualTo(5_000);
            softly.assertThat(sut.getEstimatedDeliveryStart()).isEqualTo(LocalDate.of(2026, 10, 23));
            softly.assertThat(sut.getEstimatedDeliveryEnd()).isEqualTo(LocalDate.of(2026, 10, 26));
        });
    }

    @Test
    void 결제된_추가_운임이_지역_판정과_달라도_결제_값을_저장한다() {
        // given: 주문 생성 뒤 우편번호 데이터가 바뀌어 일반 지역으로 판정되는 경우
        ShipmentCreateCommand command = command(5L, PAID_AT, GENERAL_POSTAL_CODE, seller(10L, 3_000L, 501L));

        // when
        shipmentService.create(command);

        // then
        Shipment sut = shipmentsOf(5L).get(0);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getRegionGroup()).isEqualTo("GENERAL");
            softly.assertThat(sut.getRegionSurcharge()).isEqualTo(3_000);
        });
    }

    @Test
    void 출고_마감_이후_결제면_다음_영업일_출고로_도착_예정일을_계산한다() {
        // given: 금요일 14:00 결제 → 월요일 출고
        LocalDateTime paidAt = LocalDateTime.of(2026, 10, 23, 14, 0);
        ShipmentCreateCommand command = command(6L, paidAt, GENERAL_POSTAL_CODE, seller(10L, 0L, 601L));

        // when
        shipmentService.create(command);

        // then
        Shipment sut = shipmentsOf(6L).get(0);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getEstimatedDeliveryStart()).isEqualTo(LocalDate.of(2026, 10, 28));
            softly.assertThat(sut.getEstimatedDeliveryEnd()).isEqualTo(LocalDate.of(2026, 10, 29));
        });
    }

    @Test
    void 같은_주문을_두_번_처리해도_판매자별_shipment는_한_건이다() {
        // given
        ShipmentCreateCommand command = command(7L, PAID_AT, GENERAL_POSTAL_CODE,
                seller(10L, 0L, 701L), seller(20L, 0L, 702L));
        shipmentService.create(command);

        // when
        shipmentService.create(command);

        // then
        assertThat(shipmentsOf(7L)).extracting(Shipment::getSellerId).containsExactly(10L, 20L);
    }

    @Test
    void 배송지_검증에_실패하면_shipment를_만들지_않는다() {
        // given: 연락처가 숫자 10~11자리가 아니다
        AddressCommand address = new AddressCommand("홍길동", "12345", GENERAL_POSTAL_CODE, "주소", "상세 주소", null);
        ShipmentCreateCommand command = new ShipmentCreateCommand(8L, 100L, PAID_AT, address, List.of(seller(10L, 0L, 801L)));

        // when & then
        assertThatThrownBy(() -> shipmentService.create(command))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_RECIPIENT_PHONE));
        assertThat(shipmentsOf(8L)).isEmpty();
    }

    @Test
    void 금액이_int_범위를_넘으면_shipment를_만들지_않는다() {
        // given: 컬럼이 INT라 저장할 수 없는 단가
        ShipmentItemCommand item = new ShipmentItemCommand(901L, "상품", null, 1, Integer.MAX_VALUE + 1L);
        SellerShipmentCommand seller = new SellerShipmentCommand(10L, 3_000L, 50_000L, 0L, List.of(item));
        ShipmentCreateCommand command = command(9L, PAID_AT, GENERAL_POSTAL_CODE, seller);

        // when & then
        assertThatThrownBy(() -> shipmentService.create(command))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("integer overflow");
        assertThat(shipmentsOf(9L)).isEmpty();
    }

    private static ShipmentCreateCommand command(Long orderId, LocalDateTime paidAt, String postalCode, SellerShipmentCommand... sellers) {
        AddressCommand address = new AddressCommand("홍길동", "010-1234-5678", postalCode, "주소", "상세 주소", null);
        return new ShipmentCreateCommand(orderId, 100L, paidAt, address, List.of(sellers));
    }

    private static SellerShipmentCommand seller(Long sellerId, Long regionSurcharge, Long... orderItemIds) {
        List<ShipmentItemCommand> items = Arrays.stream(orderItemIds)
                .map(id -> new ShipmentItemCommand(id, "상품" + id, null, 1, 10_000L))
                .toList();
        return new SellerShipmentCommand(sellerId, 3_000L, 50_000L, regionSurcharge, items);
    }

    // 다른 테스트 데이터에 영향받지 않게 해당 주문의 행만 고른다. findAllById는 순서를 보장하지 않아 판매자 순으로 정렬한다
    private List<Shipment> shipmentsOf(Long orderId) {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM shipment WHERE order_id = ?", Long.class, orderId);
        return shipmentRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing(Shipment::getSellerId))
                .toList();
    }

    // 항목은 지연 로딩이라 트랜잭션 밖에서 엔티티로 읽지 않고 테이블에서 바로 조회한다
    private List<Long> orderItemIdsOf(Shipment shipment) {
        return jdbcTemplate.queryForList(
                "SELECT order_item_id FROM shipment_item WHERE shipment_id = ? ORDER BY order_item_id",
                Long.class, shipment.getId());
    }
}
