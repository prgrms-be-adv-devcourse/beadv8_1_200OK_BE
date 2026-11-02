package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

/**
 * 배송 목록 조회 출력 포트. 목록에 보여 줄 상품은 shipment마다 따로 읽지 않도록 페이지 단위로 한 번에 읽는다.
 */
public interface ShipmentQueryRepository {

    /**
     * 구매자의 shipment를 최신순으로 조회한다.
     */
    Page<Shipment> findByBuyerId(Long buyerId, Pageable pageable);

    /**
     * 판매자의 shipment를 최신순으로 조회한다.
     */
    Page<Shipment> findBySellerId(Long sellerId, Pageable pageable);

    /**
     * 여러 shipment의 상품 항목을 한 번에 조회한다. 항목은 shipment 안에서 만든 순서(id)대로다.
     */
    List<ShipmentItem> findItemsByShipmentIds(Collection<Long> shipmentIds);
}
