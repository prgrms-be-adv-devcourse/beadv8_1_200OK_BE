package com.ok.delivery.repository;

import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShipmentAddressChangeRequestRepository extends JpaRepository<ShipmentAddressChangeRequest, Long> {

    boolean existsByShipmentIdAndStatus(Long shipmentId, AddressChangeStatus status);

    List<ShipmentAddressChangeRequest> findAllByShipmentIdAndStatus(Long shipmentId, AddressChangeStatus status);

    Optional<ShipmentAddressChangeRequest> findByIdAndShipmentId(Long id, Long shipmentId);

    // 요청 시각이 같으면 나중에 저장된 요청을 최근으로 본다
    Optional<ShipmentAddressChangeRequest> findFirstByShipmentIdOrderByRequestedAtDescIdDesc(Long shipmentId);

    /**
     * 해당 상태인 요청의 추가 운임 안내 금액 합계를 조회한다. 요청이 없으면 0이다.
     */
    @Query("""
            select coalesce(sum(r.additionalFeeNotice), 0)
            from ShipmentAddressChangeRequest r
            where r.shipmentId = :shipmentId and r.status = :status
            """)
    long sumAdditionalFeeNotice(@Param("shipmentId") Long shipmentId, @Param("status") AddressChangeStatus status);
}
