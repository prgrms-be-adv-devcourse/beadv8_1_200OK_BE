package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentStatus;

import java.util.List;
import java.util.Optional;

/**
 * shipment 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ShipmentRepository {

    boolean existsByOrderId(Long orderId);

    <S extends Shipment> List<S> saveAll(Iterable<S> entities);

    List<Shipment> findAllByStatus(ShipmentStatus status);

    Optional<Shipment> findById(Long id);

    /**
     * 택배사와 송장번호로 shipment를 조회한다. 같은 택배사의 송장번호는 유일하다({@code uk_shipment_carrier_tracking_number}).
     */
    Optional<Shipment> findByCarrierAndTrackingNumber(Carrier carrier, String trackingNumber);

    /**
     * shipment 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다.
     * shipment 상태나 배송지 변경 요청을 바꾸는 트랜잭션은 이 메서드를 첫 쿼리로 호출해야 한다.
     * 앞서 일반 조회를 하면 REPEATABLE READ 스냅샷이 잠금 전에 고정되어, 잠금을 얻은 뒤에도 앞 트랜잭션이 커밋한 행이 보이지 않는다.
     */
    Optional<Shipment> findByIdForUpdate(Long id);

    /**
     * 주문 상품이 담긴 shipment를 쓰기 잠금으로 조회한다. 주문 상품은 한 shipment에만 담긴다({@code shipment_item.order_item_id} UNIQUE).
     * 잠금 계약은 {@link #findByIdForUpdate}와 같다.
     * 주문 상품으로 shipment를 찾는 읽기까지 잠금 읽기여야 한다. 찾는 읽기가 일반 조회면 그 읽기가 스냅숏을 고정해,
     * 같은 shipment의 다른 항목을 앞 트랜잭션이 확정해도 보이지 않는다. 그래서 그 항목 행도 함께 잠근다(항목 행 → shipment 행 순서).
     */
    Optional<Shipment> findByOrderItemIdForUpdate(Long orderItemId);

    void flush();
}
