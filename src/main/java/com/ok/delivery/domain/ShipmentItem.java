package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseIdAndTime;
import com.ok.delivery.exception.DeliveryErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * shipment에 담긴 주문 상품 항목. 상품명, 옵션명, 단가는 결제 시점 값이다.
 *
 * <p>취소는 수량 단위로 누적하고, 구매확정은 항목 단위로 한다.
 * shipment의 거래완료·취소 조건이 이 두 값으로 정해진다.
 */
@Entity
@Getter
@Table(name = "shipment_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentItem extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "order_item_id", nullable = false, unique = true)
    private Long orderItemId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "option_name")
    private String optionName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    @Column(name = "canceled_quantity", nullable = false)
    private int canceledQuantity;

    @Column(name = "confirmed", nullable = false)
    private boolean confirmed;

    private ShipmentItem(Long orderItemId, String productName, String optionName, int quantity, int unitPrice) {
        if (orderItemId == null) {
            throw new IllegalArgumentException("orderItemId는 필수입니다.");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("상품명은 필수입니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다.");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("단가는 0 이상이어야 합니다.");
        }
        this.orderItemId = orderItemId;
        this.productName = productName;
        this.optionName = optionName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.canceledQuantity = 0;
        this.confirmed = false;
    }

    /** 항목을 만든다. shipment 연결은 {@link Shipment} 생성 시 이루어진다. */
    public static ShipmentItem create(Long orderItemId, String productName, String optionName, int quantity, int unitPrice) {
        return new ShipmentItem(orderItemId, productName, optionName, quantity, unitPrice);
    }

    /**
     * 구매확정한다. 이미 확정된 항목이면 그대로 둔다.
     *
     * @throws RestApiException 전량 취소된 항목이면 {@code INVALID_SHIPMENT_STATE}
     */
    public void confirm() {
        if (isFullyCanceled()) {
            throw new RestApiException(DeliveryErrorCode.INVALID_SHIPMENT_STATE);
        }
        this.confirmed = true;
    }

    /**
     * 취소 확정된 수량을 누적한다.
     *
     * @throws IllegalArgumentException 수량이 1 미만이거나 누적 취소 수량이 주문 수량을 넘으면
     */
    public void cancel(int cancelQuantity) {
        if (cancelQuantity <= 0) {
            throw new IllegalArgumentException("취소 수량은 1 이상이어야 합니다.");
        }
        if (canceledQuantity + cancelQuantity > quantity) {
            throw new IllegalArgumentException("취소 수량이 주문 수량을 넘을 수 없습니다.");
        }
        this.canceledQuantity += cancelQuantity;
    }

    /** 주문 수량이 모두 취소되었는지 여부. 전량 취소된 항목은 거래완료 조건에서 빠진다. */
    public boolean isFullyCanceled() {
        return canceledQuantity == quantity;
    }

    void assignTo(Shipment shipment) {
        if (this.shipment != null) {
            throw new IllegalStateException("이미 다른 shipment에 속한 항목입니다.");
        }
        this.shipment = shipment;
    }
}
