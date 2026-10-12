package com.ok.delivery.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.domain.Address;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.repository.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ShipmentAddressChangeService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentAddressChangeRequestRepository requestRepository;

    @Transactional
    public ShipmentAddressChangeRequest request(Long shipmentId, Address requestedAddress, int additionalFeeNotice) {
        Shipment shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.SHIPMENT_NOT_FOUND));
        if (requestRepository.existsByShipmentIdAndStatus(shipmentId, AddressChangeStatus.PENDING)) {
            throw new RestApiException(DeliveryErrorCode.ADDRESS_CHANGE_ALREADY_PENDING);
        }
        ShipmentAddressChangeRequest request = shipment.requestAddressChange(requestedAddress, additionalFeeNotice, LocalDateTime.now());
        return requestRepository.save(request);
    }
}
