package com.ok.delivery.repository;

import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShipmentAddressChangeRequestRepository extends JpaRepository<ShipmentAddressChangeRequest, Long> {

    boolean existsByShipmentIdAndStatus(Long shipmentId, AddressChangeStatus status);

    List<ShipmentAddressChangeRequest> findAllByShipmentIdAndStatus(Long shipmentId, AddressChangeStatus status);

    Optional<ShipmentAddressChangeRequest> findByIdAndShipmentId(Long id, Long shipmentId);
}
