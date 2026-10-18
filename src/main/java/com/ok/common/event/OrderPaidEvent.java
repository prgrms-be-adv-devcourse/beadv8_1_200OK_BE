package com.ok.common.event;

import com.ok.common.event.payload.PaidOrderItem;
import com.ok.common.event.payload.PaidSellerOrder;
import com.ok.common.event.payload.ShippingAddress;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 주문의 결제 완료를 알린다. 주문이 정의하고 발행하며, 배송은 이 이벤트로 판매자별 shipment를 만든다.
 * 배송은 주문을 따로 조회하지 않으므로 shipment에 필요한 결제 시점 값을 모두 담는다.
 *
 * @param orderId         주문 ID
 * @param paidAt          결제 완료 시각. 배송의 출고·도착 예정일 계산 기준
 * @param shippingAddress 결제 시점에 확정된 배송지
 * @param sellerOrders    판매자별 상품과 배송비. 판매자 하나가 shipment(송장) 하나가 된다
 */
public record OrderPaidEvent(
        Long orderId,
        LocalDateTime paidAt,
        ShippingAddress shippingAddress,
        List<PaidSellerOrder> sellerOrders
) implements DomainEvent {

    public OrderPaidEvent {
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(paidAt, "paidAt은 필수입니다.");
        Objects.requireNonNull(shippingAddress, "shippingAddress는 필수입니다.");
        if (sellerOrders == null || sellerOrders.isEmpty()) {
            throw new IllegalArgumentException("sellerOrders는 1개 이상이어야 합니다.");
        }
        // 발행 후 목록이 바뀌지 않게 복사한다. List.copyOf는 null 원소도 거부한다
        sellerOrders = List.copyOf(sellerOrders);
        // 배송은 (주문, 판매자)당 shipment 1건, 주문 항목당 shipment 항목 1건만 만들고 중복은 이미 처리된 것으로 본다.
        // 판매자나 주문 항목이 두 번 오면 뒤에 온 상품이 배송되지 않으므로 만들 때 막는다
        Set<Long> sellerIds = new HashSet<>();
        Set<Long> orderItemIds = new HashSet<>();
        for (PaidSellerOrder sellerOrder : sellerOrders) {
            if (!sellerIds.add(sellerOrder.sellerId())) {
                throw new IllegalArgumentException("sellerId가 중복되었습니다: " + sellerOrder.sellerId());
            }
            for (PaidOrderItem item : sellerOrder.items()) {
                if (!orderItemIds.add(item.orderItemId())) {
                    throw new IllegalArgumentException("orderItemId가 중복되었습니다: " + item.orderItemId());
                }
            }
        }
    }
}
