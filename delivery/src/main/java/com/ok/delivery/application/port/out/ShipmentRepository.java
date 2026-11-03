package com.ok.delivery.application.port.out;

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
     * shipment 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다.
     * shipment 상태나 배송지 변경 요청을 바꾸는 트랜잭션은 이 메서드를 첫 쿼리로 호출해야 한다.
     * 앞서 일반 조회를 하면 REPEATABLE READ 스냅샷이 잠금 전에 고정되어, 잠금을 얻은 뒤에도 앞 트랜잭션이 커밋한 행이 보이지 않는다.
     */
    Optional<Shipment> findByIdForUpdate(Long id);

    void flush();
}
