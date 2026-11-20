package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.ShipmentAddressChangeRequestRepository;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface ShipmentAddressChangeRequestJpaRepository
        extends JpaRepository<ShipmentAddressChangeRequest, Long>, ShipmentAddressChangeRequestRepository {

    @Override
    @Query("""
            select distinct r.shipmentId
            from ShipmentAddressChangeRequest r
            where r.shipmentId in :shipmentIds and r.status = :status
            """)
    Set<Long> findShipmentIdsWithStatus(@Param("shipmentIds") Collection<Long> shipmentIds, @Param("status") AddressChangeStatus status);

    @Override
    @Query("""
            select coalesce(sum(r.additionalFeeNotice), 0)
            from ShipmentAddressChangeRequest r
            where r.shipmentId = :shipmentId and r.status = :status
            """)
    long sumAdditionalFeeNotice(@Param("shipmentId") Long shipmentId, @Param("status") AddressChangeStatus status);
}
