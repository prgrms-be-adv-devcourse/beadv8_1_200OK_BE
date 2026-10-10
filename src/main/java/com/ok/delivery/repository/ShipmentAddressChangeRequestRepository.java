package com.ok.delivery.repository;

import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentAddressChangeRequestRepository extends JpaRepository<ShipmentAddressChangeRequest, Long> {

    boolean existsByShipmentIdAndStatus(Long shipmentId, AddressChangeStatus status);
}
