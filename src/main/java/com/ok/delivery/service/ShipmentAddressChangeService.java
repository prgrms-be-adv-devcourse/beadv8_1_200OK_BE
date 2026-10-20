package com.ok.delivery.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.repository.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.dto.AddressCommand;
import com.ok.delivery.service.dto.RegionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipmentAddressChangeService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentAddressChangeRequestRepository requestRepository;
    private final DeliveryRegionService deliveryRegionService;
    private final Clock clock;

    /**
     * 배송지 변경 요청을 만든다.
     * shipment 행 잠금 아래에서 대기 중인 요청을 확인하므로, 동시에 요청해도 {@code PENDING}은 shipment당 1건만 생긴다.
     * 추가 운임 안내 금액은 요청 배송지의 지역을 판정해 {@link Shipment#requestAddressChange}가 계산한다.
     *
     * @throws RestApiException shipment가 없으면 {@code SHIPMENT_NOT_FOUND},
     *                          대기 중인 요청이 있으면 {@code ADDRESS_CHANGE_ALREADY_PENDING},
     *                          {@code PREPARING}이 아니면 {@code ADDRESS_CHANGE_NOT_ALLOWED},
     *                          배송지 검증에 실패하면 {@link Address#of}의 에러 코드
     */
    @Transactional
    public ShipmentAddressChangeRequest request(Long shipmentId, AddressCommand requestedAddress) {
        Shipment shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.SHIPMENT_NOT_FOUND));
        if (requestRepository.existsByShipmentIdAndStatus(shipmentId, AddressChangeStatus.PENDING)) {
            throw new RestApiException(DeliveryErrorCode.ADDRESS_CHANGE_ALREADY_PENDING);
        }
        Address address = requestedAddress.toAddress();
        RegionResult region = deliveryRegionService.resolve(address.getPostalCode());
        ShipmentAddressChangeRequest request = shipment.requestAddressChange(address, region.surcharge(), LocalDateTime.now(clock));
        return requestRepository.save(request);
    }
}
