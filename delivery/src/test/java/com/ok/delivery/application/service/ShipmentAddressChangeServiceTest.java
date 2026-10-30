package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.AddressChangeDecision;
import com.ok.delivery.application.dto.AddressChangeDecisionCommand;
import com.ok.delivery.application.dto.AddressChangeRequestResult;
import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.port.out.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.application.port.out.ShipmentRepository;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.delivery.domain.WeekendBusinessDayCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import static com.ok.delivery.fixture.ShipmentFixture.address;
import static com.ok.delivery.fixture.ShipmentFixture.otherAddress;
import static com.ok.delivery.fixture.ShipmentFixture.shipmentIn;
import static com.ok.delivery.fixture.ShipmentFixture.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ShipmentAddressChangeServiceTest {

    // 2026-10-20(화) 12:00 승인. 판매자 마감 11:00이 지나 다음 영업일(수) 출고로 다시 계산된다
    private static final LocalDateTime TUESDAY_NOON = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final Long SHIPMENT_ID = 1L;
    private static final Long REQUEST_ID = 5L;
    private static final Long SELLER_ID = 10L;
    private static final SellerShippingSetting SELLER_SETTING = new SellerShippingSetting(3_000, 50_000, LocalTime.of(11, 0));

    @Mock
    ShipmentRepository shipmentRepository;

    @Mock
    ShipmentAddressChangeRequestRepository requestRepository;

    @Mock
    DeliveryRegionService deliveryRegionService;

    @Mock
    SellerShippingSettingService sellerShippingSettingService;

    ShipmentAddressChangeService shipmentAddressChangeService;

    Shipment shipment;

    ShipmentAddressChangeRequest request;

    @BeforeEach
    void setUp() {
        // 날짜 계산은 실제 정책으로 두고, 서비스가 승인 시각과 판매자 마감 시간을 넘기는지 확인한다
        Clock clock = Clock.fixed(TUESDAY_NOON.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        shipmentAddressChangeService = new ShipmentAddressChangeService(shipmentRepository, requestRepository, deliveryRegionService,
                new DeliveryDatePolicy(new WeekendBusinessDayCalendar()), sellerShippingSettingService, clock);
        shipment = withId(shipmentIn(ShipmentStatus.PREPARING), SHIPMENT_ID);
        request = shipment.requestAddressChange(otherAddress(), 3_000, TUESDAY_NOON.minusHours(1));
        given(shipmentRepository.findByIdForUpdate(SHIPMENT_ID)).willReturn(Optional.of(shipment));
        given(requestRepository.findByIdAndShipmentId(REQUEST_ID, SHIPMENT_ID)).willReturn(Optional.of(request));
    }

    @Test
    void 승인하면_승인_시각과_판매자_마감_시간으로_도착_예정일을_다시_계산한다() {
        // given
        given(deliveryRegionService.resolve("63100")).willReturn(new RegionResult("JEJU_MAIN", 3_000, 1));
        given(sellerShippingSettingService.getSetting(SELLER_ID)).willReturn(SELLER_SETTING);

        // when
        AddressChangeRequestResult sut = shipmentAddressChangeService.decide(command(AddressChangeDecision.APPROVE));

        // then: 수요일 출고 + (최소 2 + 제주 1)영업일 = 다음 주 월요일 도착 시작
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(AddressChangeStatus.APPROVED.name());
            softly.assertThat(sut.processedAt()).isEqualTo(TUESDAY_NOON);
            softly.assertThat(shipment.getAddress()).isEqualTo(otherAddress());
            softly.assertThat(shipment.getRegionGroup()).isEqualTo("JEJU_MAIN");
            softly.assertThat(shipment.getRegionSurcharge()).isEqualTo(3_000);
            softly.assertThat(shipment.getEstimatedShipDate()).isEqualTo(LocalDate.of(2026, 10, 21));
            softly.assertThat(shipment.getEstimatedDeliveryStart()).isEqualTo(LocalDate.of(2026, 10, 26));
            softly.assertThat(shipment.getEstimatedDeliveryEnd()).isEqualTo(LocalDate.of(2026, 10, 27));
        });
    }

    @Test
    void 요청_뒤에_지역_정책이_바뀌면_지역과_도착_예정일은_승인_시점_값을_쓰고_추가_운임은_안내한_금액만_더한다() {
        // given: 요청 때는 제주 본섬(3,000원, 1일)으로 안내했는데 승인 전에 부속 도서(5,000원, 2일)로 재지정되었다
        given(deliveryRegionService.resolve("63100")).willReturn(new RegionResult("JEJU_ISLANDS", 5_000, 2));
        given(sellerShippingSettingService.getSetting(SELLER_ID)).willReturn(SELLER_SETTING);

        // when
        shipmentAddressChangeService.decide(command(AddressChangeDecision.APPROVE));

        // then: 수요일 출고 + (최소 2 + 부속 도서 2)영업일 = 다음 주 화요일 도착 시작
        assertThat(shipment).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(shipment.getRegionGroup()).isEqualTo("JEJU_ISLANDS");
            softly.assertThat(shipment.getRegionSurcharge()).isEqualTo(3_000);
            softly.assertThat(shipment.getEstimatedDeliveryStart()).isEqualTo(LocalDate.of(2026, 10, 27));
            softly.assertThat(shipment.getEstimatedDeliveryEnd()).isEqualTo(LocalDate.of(2026, 10, 28));
        });
    }

    @Test
    void 거절하면_shipment는_바뀌지_않는다() {
        // when
        AddressChangeRequestResult sut = shipmentAddressChangeService.decide(command(AddressChangeDecision.REJECT));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(AddressChangeStatus.REJECTED.name());
            softly.assertThat(sut.rejectReason()).isEqualTo(ShipmentAddressChangeRequest.REJECT_REASON);
            softly.assertThat(shipment.getAddress()).isEqualTo(address());
            softly.assertThat(shipment.getRegionGroup()).isEqualTo("GENERAL");
            softly.assertThat(shipment.getRegionSurcharge()).isZero();
        });
    }

    private static AddressChangeDecisionCommand command(AddressChangeDecision decision) {
        return new AddressChangeDecisionCommand(SHIPMENT_ID, REQUEST_ID, SELLER_ID, decision);
    }
}
