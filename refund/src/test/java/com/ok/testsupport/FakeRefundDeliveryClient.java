package com.ok.testsupport;

import com.ok.refund.service.RefundDeliveryClient;
import com.ok.refund.service.dto.ShipmentReserveResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 배송 모듈 내부 API 대신 메모리로 배송 건과 잠금(취소 예약)을 흉내 내는 Fake.
 * 배송 건이 있는 주문 상품은 {@link #addShipment}로, 이미 출고된 상품은 {@link #markDispatched}로 정한다.
 * 테스트마다 {@link #clear}로 비운다. 동시 요청 테스트가 있어 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeRefundDeliveryClient implements RefundDeliveryClient {

    private final Set<Long> shipmentItems = ConcurrentHashMap.newKeySet();
    private final Set<Long> dispatchedItems = ConcurrentHashMap.newKeySet();
    private final List<Long> reservedRefundIds = new CopyOnWriteArrayList<>();
    private final List<Long> confirmedRefundIds = new CopyOnWriteArrayList<>();

    public void addShipment(Long orderItemId) {
        shipmentItems.add(orderItemId);
    }

    /** 송장이 등록된 상품. 잠금 요청을 409로 거절한다 */
    public void markDispatched(Long orderItemId) {
        dispatchedItems.add(orderItemId);
    }

    public List<Long> reservedRefundIds() {
        return List.copyOf(reservedRefundIds);
    }

    public List<Long> confirmedRefundIds() {
        return List.copyOf(confirmedRefundIds);
    }

    public void clear() {
        shipmentItems.clear();
        dispatchedItems.clear();
        reservedRefundIds.clear();
        confirmedRefundIds.clear();
    }

    @Override
    public boolean hasShipment(Long orderItemId) {
        return shipmentItems.contains(orderItemId);
    }

    @Override
    public ShipmentReserveResult reserveCancel(Long refundId, Long orderItemId, int quantity) {
        if (dispatchedItems.contains(orderItemId)) {
            return ShipmentReserveResult.REJECTED;
        }
        reservedRefundIds.add(refundId);
        return ShipmentReserveResult.RESERVED;
    }

    @Override
    public void confirmCancel(Long refundId) {
        confirmedRefundIds.add(refundId);
    }
}
