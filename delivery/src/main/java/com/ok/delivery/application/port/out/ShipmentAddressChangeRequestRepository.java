package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 배송지 변경 요청 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface ShipmentAddressChangeRequestRepository {

    <S extends ShipmentAddressChangeRequest> S save(S entity);

    boolean existsByShipmentIdAndStatus(Long shipmentId, AddressChangeStatus status);

    List<ShipmentAddressChangeRequest> findAllByShipmentIdAndStatus(Long shipmentId, AddressChangeStatus status);

    Optional<ShipmentAddressChangeRequest> findByIdAndShipmentId(Long id, Long shipmentId);

    /**
     * 여러 shipment 중 해당 상태의 요청이 있는 shipment ID를 조회한다. 목록에서 shipment마다 따로 확인하지 않기 위해 쓴다.
     */
    Set<Long> findShipmentIdsWithStatus(Collection<Long> shipmentIds, AddressChangeStatus status);

    // 요청 시각이 같으면 나중에 저장된 요청을 최근으로 본다
    Optional<ShipmentAddressChangeRequest> findFirstByShipmentIdOrderByRequestedAtDescIdDesc(Long shipmentId);

    /**
     * 해당 상태인 요청의 추가 운임 안내 금액 합계를 조회한다. 요청이 없으면 0이다.
     */
    long sumAdditionalFeeNotice(Long shipmentId, AddressChangeStatus status);
}
