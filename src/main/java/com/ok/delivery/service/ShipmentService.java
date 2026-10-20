package com.ok.delivery.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.DeliveryDateRange;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.repository.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.dto.RegionResult;
import com.ok.delivery.service.dto.SellerShipmentCommand;
import com.ok.delivery.service.dto.ShipmentDispatchCommand;
import com.ok.delivery.service.dto.ShipmentDispatchResult;
import com.ok.delivery.service.dto.ShipmentCreateCommand;
import com.ok.delivery.service.dto.ShipmentItemCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipmentService {

    private static final String TRACKING_NUMBER_UNIQUE_CONSTRAINT = "uk_shipment_carrier_tracking_number";

    private final ShipmentRepository shipmentRepository;
    private final ShipmentAddressChangeRequestRepository addressChangeRequestRepository;
    private final DeliveryRegionService deliveryRegionService;
    private final DeliveryDatePolicy deliveryDatePolicy;
    private final SellerShippingSettingService sellerShippingSettingService;
    private final DeliveryEventPublisher deliveryEventPublisher;
    private final Clock clock;

    /**
     * 결제 완료된 주문으로 판매자별 shipment를 {@code PREPARING} 상태로 만든다.
     * 배송비 금액은 결제에 쓰인 값을 그대로 저장하고, 지역 그룹과 도착 예정일은 배송지 우편번호로 판정한다.
     * 이미 shipment가 있는 주문이면 만들지 않는다(이벤트 재발행 대비).
     *
     * @throws com.ok.common.exception.RestApiException 배송지 검증 규칙은 {@link Address#of}를 따른다
     * @throws ArithmeticException 금액이 {@code int} 범위를 넘으면
     */
    @Transactional
    public void create(ShipmentCreateCommand command) {
        // 판매자 묶음 전체를 한 트랜잭션에 저장하므로 주문 단위로 확인하면 된다.
        // 동시에 두 번 처리되는 드문 경우는 (order_id, seller_id) 유니크 제약이 막고, 실패한 쪽은 재처리 때 이 확인에서 무시된다
        if (shipmentRepository.existsByOrderId(command.orderId())) {
            log.info("이미 처리된 결제 완료 이벤트, 무시. orderId={}", command.orderId());
            return;
        }
        Address address = command.address().toAddress();
        RegionResult region = deliveryRegionService.resolve(address.getPostalCode());
        warnIfSurchargeMismatched(command, address, region);
        // 견적과 같은 판매자 설정에서 마감 시간을 읽어야 견적에서 보여 준 도착 예정일과 어긋나지 않는다
        Map<Long, SellerShippingSetting> settings = sellerShippingSettingService.getSettings(
                command.sellers().stream().map(SellerShipmentCommand::sellerId).toList());
        List<Shipment> shipments = command.sellers().stream()
                .map(seller -> toShipment(command, address, region, seller, settings.get(seller.sellerId()).cutoffTime()))
                .toList();
        shipmentRepository.saveAll(shipments);
    }

    /**
     * 판매자가 송장을 등록해 출고한다. {@code PREPARING → SHIPPING}
     * shipment 행 잠금 아래에서 처리하므로 환불의 취소 확정 예약과 동시에 실행돼도 한쪽만 성공한다.
     * 대기 중인 배송지 변경 요청은 송장 발행 후 바꿀 수 없으므로 고정 문구로 자동 거절한다.
     * 출고가 확정되면 주문이 배송 중으로 바뀌도록 배송 시작 이벤트를 발행한다.
     *
     * @throws RestApiException 없거나 다른 판매자의 shipment면 {@code SHIPMENT_NOT_FOUND},
     *                          같은 택배사의 송장번호가 이미 쓰였으면 {@code TRACKING_NUMBER_DUPLICATED},
     *                          상태·송장번호 형식·취소 예약 규칙은 {@link Shipment#dispatch}를 따른다
     */
    @Transactional
    public ShipmentDispatchResult dispatch(ShipmentDispatchCommand command) {
        // 다른 판매자의 shipment는 존재 여부를 숨기려고 없는 것과 같게 응답한다
        Shipment shipment = shipmentRepository.findByIdForUpdate(command.shipmentId())
                .filter(found -> found.getSellerId().equals(command.sellerId()))
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.SHIPMENT_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        shipment.dispatch(Carrier.from(command.carrier()), command.trackingNumber(), now);
        flushDetectingDuplicatedTrackingNumber();
        addressChangeRequestRepository.findAllByShipmentIdAndStatus(shipment.getId(), AddressChangeStatus.PENDING)
                .forEach(request -> request.reject(now));
        // 송장번호 중복 확인(flush)을 통과한 뒤에 발행한다. 실패해 롤백되면 커밋 후 수신하는 주문에는 전달되지 않는다
        deliveryEventPublisher.publishShipmentDispatched(shipment.getId(), shipment.getOrderId(), shipment.getSellerId(),
                shipment.getCarrier().name(), shipment.getTrackingNumber(), shipment.getShippedAt());
        return ShipmentDispatchResult.from(shipment);
    }

    // 송장번호 중복은 사전 조회 없이 UNIQUE 제약 하나로 막는다. 사전 조회해도 동시 등록은 제약이 막아야 하므로 확인 경로를 하나로 둔다.
    // 다른 제약 위반까지 중복으로 바꾸지 않도록 제약 이름을 확인하고, 아니면 원래 예외를 그대로 던진다
    private void flushDetectingDuplicatedTrackingNumber() {
        try {
            shipmentRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(TRACKING_NUMBER_UNIQUE_CONSTRAINT)) {
                throw new RestApiException(DeliveryErrorCode.TRACKING_NUMBER_DUPLICATED);
            }
            throw e;
        }
    }

    private Shipment toShipment(ShipmentCreateCommand command, Address address, RegionResult region,
                                SellerShipmentCommand seller, LocalTime cutoffTime) {
        LocalDate shipDate = deliveryDatePolicy.shipDate(command.paidAt(), cutoffTime);
        DeliveryDateRange arrival = deliveryDatePolicy.arrivalRange(shipDate, region.extraDeliveryDays());
        return Shipment.builder()
                .orderId(command.orderId())
                .buyerId(command.buyerId())
                .sellerId(seller.sellerId())
                .address(address)
                .regionGroup(region.regionGroup())
                .baseShippingFee(Math.toIntExact(seller.baseShippingFee()))
                .regionSurcharge(Math.toIntExact(seller.regionSurcharge()))
                .freeShippingThreshold(Math.toIntExact(seller.freeShippingThreshold()))
                .estimatedDeliveryStart(arrival.start())
                .estimatedDeliveryEnd(arrival.end())
                .items(seller.items().stream().map(ShipmentService::toItem).toList())
                .build();
    }

    // 추가 운임은 결제 금액과 맞추려고 이벤트 값을 저장한다. 판정 값과 다르면 주문 생성 뒤 우편번호 데이터가 바뀐 것이라 확인할 수 있게 남긴다.
    // 배송지가 하나라 판정도 하나이므로 판매자마다 남기지 않고 주문당 한 번 남긴다
    private static void warnIfSurchargeMismatched(ShipmentCreateCommand command, Address address, RegionResult region) {
        List<Long> mismatchedSellerIds = command.sellers().stream()
                .filter(seller -> seller.regionSurcharge() != region.surcharge())
                .map(SellerShipmentCommand::sellerId)
                .toList();
        if (!mismatchedSellerIds.isEmpty()) {
            log.warn("결제된 추가 운임과 지역 판정 운임이 다름, 결제 값으로 저장. orderId={}, postalCode={}, resolved={}, sellerIds={}",
                    command.orderId(), address.getPostalCode(), region.surcharge(), mismatchedSellerIds);
        }
    }

    private static ShipmentItem toItem(ShipmentItemCommand item) {
        return ShipmentItem.create(
                item.orderItemId(),
                item.productName(),
                item.optionName(),
                item.quantity(),
                Math.toIntExact(item.unitPrice()));
    }
}
