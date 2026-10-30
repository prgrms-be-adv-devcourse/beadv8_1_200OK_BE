package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.AddressChangeDecision;
import com.ok.delivery.application.dto.AddressChangeDecisionCommand;
import com.ok.delivery.application.dto.AddressChangeRequestCommand;
import com.ok.delivery.application.dto.AddressChangeRequestResult;
import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.port.in.ShipmentAddressChangeUseCase;
import com.ok.delivery.application.port.out.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.application.port.out.ShipmentRepository;
import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.DeliveryDateRange;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.delivery.exception.DeliveryErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipmentAddressChangeService implements ShipmentAddressChangeUseCase {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentAddressChangeRequestRepository requestRepository;
    private final DeliveryRegionService deliveryRegionService;
    private final DeliveryDatePolicy deliveryDatePolicy;
    private final SellerShippingSettingService sellerShippingSettingService;
    private final Clock clock;

    @Override
    @Transactional
    public AddressChangeRequestResult request(AddressChangeRequestCommand command) {
        // 다른 구매자의 shipment는 존재 여부를 숨기려고 없는 것과 같게 응답한다
        Shipment shipment = shipmentRepository.findByIdForUpdate(command.shipmentId())
                .filter(found -> found.getBuyerId().equals(command.buyerId()))
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.SHIPMENT_NOT_FOUND));
        if (requestRepository.existsByShipmentIdAndStatus(shipment.getId(), AddressChangeStatus.PENDING)) {
            throw new RestApiException(DeliveryErrorCode.ADDRESS_CHANGE_ALREADY_PENDING);
        }
        Address address = command.requestedAddress().toAddress();
        RegionResult region = deliveryRegionService.resolve(address.getPostalCode());
        ShipmentAddressChangeRequest request = shipment.requestAddressChange(address, region.surcharge(), LocalDateTime.now(clock));
        return AddressChangeRequestResult.from(requestRepository.save(request));
    }

    @Override
    @Transactional
    public AddressChangeRequestResult decide(AddressChangeDecisionCommand command) {
        Shipment shipment = shipmentRepository.findByIdForUpdate(command.shipmentId())
                .filter(found -> found.getSellerId().equals(command.sellerId()))
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.SHIPMENT_NOT_FOUND));
        ShipmentAddressChangeRequest request = requestRepository.findByIdAndShipmentId(command.requestId(), shipment.getId())
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.ADDRESS_CHANGE_REQUEST_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        if (command.decision() == AddressChangeDecision.APPROVE) {
            approve(shipment, request, now);
        } else {
            request.reject(now);
        }
        return AddressChangeRequestResult.from(request);
    }

    // 처리된 요청인지 먼저 확인해야 송장 발행으로 자동 거절된 요청에 상태 오류 대신 ALREADY_PROCESSED를 돌려준다.
    // shipment 반영이 실패하면 트랜잭션이 롤백되어 승인도 남지 않는다.
    // 지역 그룹과 추가 소요일은 승인 시점에 다시 판정한다. 실제 배송과 도착 예정일에 쓰는 값이라 최신 정책을 따라야 한다.
    // 반면 추가 운임 기준에 더하는 안내 금액은 구매자에게 이미 알린 금액이라 요청 시점 값을 그대로 쓴다
    private void approve(Shipment shipment, ShipmentAddressChangeRequest request, LocalDateTime now) {
        request.approve(now);
        RegionResult region = deliveryRegionService.resolve(request.getRequestedAddress().getPostalCode());
        LocalTime cutoffTime = sellerShippingSettingService.getSetting(shipment.getSellerId()).cutoffTime();
        LocalDate shipDate = deliveryDatePolicy.shipDate(now, cutoffTime);
        DeliveryDateRange arrival = deliveryDatePolicy.arrivalRange(shipDate, region.extraDeliveryDays());
        shipment.applyAddressChange(request, region.regionGroup(), shipDate, arrival);
    }
}
