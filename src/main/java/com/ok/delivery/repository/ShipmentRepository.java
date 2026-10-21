package com.ok.delivery.repository;

import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    boolean existsByOrderId(Long orderId);

    boolean existsByCarrierAndTrackingNumber(Carrier carrier, String trackingNumber);

    List<Shipment> findAllByStatus(ShipmentStatus status);

    /**
     * shipment 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다.
     * shipment 상태나 배송지 변경 요청을 바꾸는 트랜잭션은 이 메서드를 첫 쿼리로 호출해야 한다.
     * 앞서 일반 조회를 하면 REPEATABLE READ 스냅샷이 잠금 전에 고정되어, 잠금을 얻은 뒤에도 앞 트랜잭션이 커밋한 행이 보이지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> findByIdForUpdate(@Param("id") Long id);
}
