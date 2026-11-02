package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.ShipmentRepository;
import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShipmentJpaRepository extends JpaRepository<Shipment, Long>, ShipmentRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    Optional<Shipment> findById(Long id);

    boolean existsByCarrierAndTrackingNumber(Carrier carrier, String trackingNumber);

    // 잠금 조회의 계약(첫 쿼리로 호출)은 포트 메서드에 적혀 있다
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> findByIdForUpdate(@Param("id") Long id);
}
