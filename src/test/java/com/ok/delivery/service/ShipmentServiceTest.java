package com.ok.delivery.service;

import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.WeekendBusinessDayCalendar;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.dto.AddressCommand;
import com.ok.delivery.service.dto.RegionResult;
import com.ok.delivery.service.dto.SellerShipmentCommand;
import com.ok.delivery.service.dto.ShipmentCreateCommand;
import com.ok.delivery.service.dto.ShipmentItemCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    // 2026-10-16(금) 12:00 결제. 마감 13:00 판매자는 당일 출고, 마감 11:00 판매자는 다음 영업일(월) 출고
    private static final LocalDateTime FRIDAY_NOON = LocalDateTime.of(2026, 10, 16, 12, 0);
    private static final LocalDate NEXT_TUESDAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate NEXT_WEDNESDAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate NEXT_THURSDAY = LocalDate.of(2026, 10, 22);

    private static final String POSTAL_CODE = "06236";

    @Mock
    ShipmentRepository shipmentRepository;

    @Mock
    DeliveryRegionService deliveryRegionService;

    @Mock
    SellerShippingSettingService sellerShippingSettingService;

    @Captor
    ArgumentCaptor<List<Shipment>> shipmentsCaptor;

    ShipmentService shipmentService;

    @BeforeEach
    void setUp() {
        // 날짜 계산은 실제 정책으로 두고, 서비스가 판매자 설정의 마감 시간을 넘기는지 확인한다
        shipmentService = new ShipmentService(shipmentRepository, deliveryRegionService,
                new DeliveryDatePolicy(new WeekendBusinessDayCalendar()), sellerShippingSettingService);
    }

    @Test
    void 판매자_설정의_마감_시간으로_판매자마다_도착_예정일을_계산한다() {
        // given
        given(deliveryRegionService.resolve(POSTAL_CODE)).willReturn(RegionResult.general());
        given(sellerShippingSettingService.getSettings(List.of(10L, 20L))).willReturn(Map.of(
                10L, new SellerShippingSetting(3_000, 50_000, LocalTime.of(13, 0)),
                20L, new SellerShippingSetting(3_000, 50_000, LocalTime.of(11, 0))));
        ShipmentCreateCommand command = new ShipmentCreateCommand(1L, FRIDAY_NOON,
                new AddressCommand("홍길동", "010-1234-5678", POSTAL_CODE, "주소", "상세 주소", null),
                List.of(seller(10L, 101L), seller(20L, 201L)));

        // when
        shipmentService.create(command);

        // then
        then(shipmentRepository).should().saveAll(shipmentsCaptor.capture());
        List<Shipment> sut = shipmentsCaptor.getValue();
        assertThat(sut).hasSize(2);
        assertSoftly(softly -> {
            softly.assertThat(sut.get(0).getSellerId()).isEqualTo(10L);
            softly.assertThat(sut.get(0).getEstimatedDeliveryStart()).isEqualTo(NEXT_TUESDAY);
            softly.assertThat(sut.get(0).getEstimatedDeliveryEnd()).isEqualTo(NEXT_WEDNESDAY);
            softly.assertThat(sut.get(1).getSellerId()).isEqualTo(20L);
            softly.assertThat(sut.get(1).getEstimatedDeliveryStart()).isEqualTo(NEXT_WEDNESDAY);
            softly.assertThat(sut.get(1).getEstimatedDeliveryEnd()).isEqualTo(NEXT_THURSDAY);
        });
    }

    private static SellerShipmentCommand seller(Long sellerId, Long orderItemId) {
        return new SellerShipmentCommand(sellerId, 3_000L, 50_000L, 0L,
                List.of(new ShipmentItemCommand(orderItemId, "상품", null, 1, 10_000L)));
    }
}
