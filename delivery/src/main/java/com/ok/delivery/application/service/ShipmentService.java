package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.DeliveryDateRange;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.application.port.out.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.application.port.out.ShipmentQueryRepository;
import com.ok.delivery.application.port.out.ShipmentRepository;
import com.ok.delivery.application.dto.LatestAddressChangeResult;
import com.ok.delivery.application.port.in.ShipmentUseCase;
import com.ok.delivery.application.port.out.DeliveryEventPublisher;
import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.dto.SellerShipmentCommand;
import com.ok.delivery.application.dto.SellerShipmentSummaryResult;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.delivery.application.dto.ShipmentDispatchResult;
import com.ok.delivery.application.dto.ShipmentCreateCommand;
import com.ok.delivery.application.dto.ShipmentDetailResult;
import com.ok.delivery.application.dto.ShipmentItemCommand;
import com.ok.delivery.application.dto.ShipmentSummaryResult;
import com.ok.delivery.application.dto.ShippingShipmentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipmentService implements ShipmentUseCase {

    private static final String TRACKING_NUMBER_UNIQUE_CONSTRAINT = "uk_shipment_carrier_tracking_number";
    // 이슈 명세로 고정한 목록 페이지 크기. 클라이언트가 바꿀 수 없다
    private static final int PAGE_SIZE = 25;

    private final ShipmentRepository shipmentRepository;
    private final ShipmentQueryRepository shipmentQueryRepository;
    private final ShipmentAddressChangeRequestRepository addressChangeRequestRepository;
    private final DeliveryRegionService deliveryRegionService;
    private final DeliveryDatePolicy deliveryDatePolicy;
    private final SellerShippingSettingService sellerShippingSettingService;
    private final DeliveryEventPublisher deliveryEventPublisher;
    private final Clock clock;

    @Override
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

    @Override
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

    /**
     * 택배사에 추적을 다시 요청할 배송 중({@code SHIPPING}) shipment 목록을 조회한다.
     */
    public List<ShippingShipmentResult> getShippingShipments() {
        return shipmentRepository.findAllByStatus(ShipmentStatus.SHIPPING).stream()
                .map(ShippingShipmentResult::from)
                .toList();
    }

    @Override
    @Transactional
    public void deliver(Long shipmentId) {
        Shipment shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.SHIPMENT_NOT_FOUND));
        shipment.deliver(LocalDateTime.now(clock));
        deliveryEventPublisher.publishShipmentDelivered(shipment.getId(), shipment.getOrderId(), shipment.getSellerId(),
                shipment.getDeliveredAt());
    }

    @Override
    public Page<ShipmentSummaryResult> getBuyerShipments(Long buyerId, int page) {
        Page<Shipment> shipments = shipmentQueryRepository.findByBuyerId(buyerId, PageRequest.of(page, PAGE_SIZE));
        Map<Long, List<ShipmentItem>> itemsByShipmentId = itemsByShipmentId(idsOf(shipments));
        return shipments.map(shipment -> ShipmentSummaryResult.of(shipment, itemsByShipmentId.get(shipment.getId())));
    }

    @Override
    public Page<SellerShipmentSummaryResult> getSellerShipments(Long sellerId, int page) {
        Page<Shipment> shipments = shipmentQueryRepository.findBySellerId(sellerId, PageRequest.of(page, PAGE_SIZE));
        List<Long> shipmentIds = idsOf(shipments);
        Map<Long, List<ShipmentItem>> itemsByShipmentId = itemsByShipmentId(shipmentIds);
        Set<Long> pendingShipmentIds = shipmentIds.isEmpty()
                ? Set.of()
                : addressChangeRequestRepository.findShipmentIdsWithStatus(shipmentIds, AddressChangeStatus.PENDING);
        return shipments.map(shipment -> SellerShipmentSummaryResult.of(
                shipment, itemsByShipmentId.get(shipment.getId()), pendingShipmentIds.contains(shipment.getId())));
    }

    @Override
    public ShipmentDetailResult getBuyerShipment(Long buyerId, Long shipmentId) {
        // 다른 구매자의 shipment는 존재 여부를 숨기려고 없는 것과 같게 응답한다
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .filter(found -> found.getBuyerId().equals(buyerId))
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.SHIPMENT_NOT_FOUND));
        return toDetail(shipment);
    }

    @Override
    public ShipmentDetailResult getSellerShipment(Long sellerId, Long shipmentId) {
        // 다른 판매자의 shipment는 존재 여부를 숨기려고 없는 것과 같게 응답한다
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .filter(found -> found.getSellerId().equals(sellerId))
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.SHIPMENT_NOT_FOUND));
        return toDetail(shipment);
    }

    private static List<Long> idsOf(Page<Shipment> shipments) {
        return shipments.getContent().stream().map(Shipment::getId).toList();
    }

    private Map<Long, List<ShipmentItem>> itemsByShipmentId(List<Long> shipmentIds) {
        return shipmentQueryRepository.findItemsByShipmentIds(shipmentIds).stream()
                .collect(Collectors.groupingBy(item -> item.getShipment().getId()));
    }

    // 구매자와 판매자 상세가 같은 응답을 쓰므로 조립을 한 곳에 둔다
    private ShipmentDetailResult toDetail(Shipment shipment) {
        long additionalShippingFee = addressChangeRequestRepository.sumAdditionalFeeNotice(shipment.getId(), AddressChangeStatus.APPROVED);
        LatestAddressChangeResult latestAddressChange = addressChangeRequestRepository.findFirstByShipmentIdOrderByRequestedAtDescIdDesc(shipment.getId())
                .map(LatestAddressChangeResult::from)
                .orElse(null);
        return ShipmentDetailResult.of(shipment, additionalShippingFee, latestAddressChange);
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
                .estimatedShipDate(shipDate)
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
