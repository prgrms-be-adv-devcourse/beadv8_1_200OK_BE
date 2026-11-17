package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.DeliverySchema;
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

    @Override
    Optional<Shipment> findByCarrierAndTrackingNumber(Carrier carrier, String trackingNumber);

    boolean existsByCarrierAndTrackingNumber(Carrier carrier, String trackingNumber);

    // 잠금 조회의 계약(첫 쿼리로 호출)은 포트 메서드에 적혀 있다
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> findByIdForUpdate(@Param("id") Long id);

    // JPQL + @Lock은 "FOR UPDATE OF s"로 나가 join한 shipment_item을 잠금 없이 읽고, 그 읽기가 잠금 대기 전에 스냅숏을 고정한다.
    // 그러면 잠금을 얻은 뒤 읽는 items에 앞 트랜잭션의 구매확정이 보이지 않아 거래완료가 빠지므로, 두 테이블을 모두 잠그는 네이티브 FOR UPDATE로 쓴다.
    // fetch join으로 items를 채우면 조건에 맞는 항목 하나만 담겨 거래완료 판정이 틀어지므로 join만 하고 items는 따로 읽는다.
    // 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
    @Override
    @Query(value = "SELECT s.* FROM " + DeliverySchema.NAME + ".shipment s JOIN " + DeliverySchema.NAME + """
            .shipment_item i ON i.shipment_id = s.id
            WHERE i.order_item_id = :orderItemId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Shipment> findByOrderItemIdForUpdate(@Param("orderItemId") Long orderItemId);
}
