package com.ok.delivery.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.DeliveryDateRange;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.repository.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.dto.AddressChangeDecision;
import com.ok.delivery.service.dto.AddressChangeDecisionCommand;
import com.ok.delivery.service.dto.AddressChangeRequestCommand;
import com.ok.delivery.service.dto.AddressChangeRequestResult;
import com.ok.delivery.service.dto.RegionResult;
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
public class ShipmentAddressChangeService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentAddressChangeRequestRepository requestRepository;
    private final DeliveryRegionService deliveryRegionService;
    private final DeliveryDatePolicy deliveryDatePolicy;
    private final SellerShippingSettingService sellerShippingSettingService;
    private final Clock clock;

    /**
     * 구매자가 배송지 변경을 요청한다.
     * shipment 행 잠금 아래에서 대기 중인 요청을 확인하므로, 동시에 요청해도 {@code PENDING}은 shipment당 1건만 생긴다.
     * 추가 운임 안내 금액은 요청 배송지의 지역을 판정해 {@link Shipment#requestAddressChange}가 계산한다.
     *
     * @throws RestApiException shipment가 없거나 다른 구매자의 shipment면 {@code SHIPMENT_NOT_FOUND},
     *                          대기 중인 요청이 있으면 {@code ADDRESS_CHANGE_ALREADY_PENDING},
     *                          {@code PREPARING}이 아니면 {@code ADDRESS_CHANGE_NOT_ALLOWED},
     *                          배송지 검증에 실패하면 {@link Address#of}의 에러 코드
     */
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

    /**
     * 판매자가 배송지 변경 요청을 승인하거나 거절한다.
     * 승인하면 shipment의 배송지, 지역 그룹, 추가 운임 기준, 도착 예정일을 요청 배송지 기준으로 바꾼다.
     * 도착 예정일은 아직 출고 전이므로 승인 시각과 판매자 마감 시간으로 출고 예정일부터 다시 계산한다.
     *
     * @throws RestApiException shipment가 없거나 다른 판매자의 shipment면 {@code SHIPMENT_NOT_FOUND},
     *                          이 shipment의 요청이 아니면 {@code ADDRESS_CHANGE_REQUEST_NOT_FOUND},
     *                          이미 처리된 요청이면 {@code ADDRESS_CHANGE_ALREADY_PROCESSED},
     *                          승인할 때 {@code PREPARING}이 아니면 {@code INVALID_SHIPMENT_STATE}
     */
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
        shipment.applyAddressChange(request, region.regionGroup(), arrival);
    }
}
