package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.delivery.exception.DeliveryErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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
public class ShipmentItem extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    // 환불이 취소를 승인하면서 걸어 둔 수량. 남아 있으면 송장 등록을 막아, 취소 승인과 출고가 함께 성공하지 않게 한다
    @Column(name = "reserved_cancel_quantity", nullable = false)
    private int reservedCancelQuantity;

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
        this.reservedCancelQuantity = 0;
        this.confirmed = false;
    }

    /**
     * 항목을 만든다. shipment 연결은 {@link Shipment} 생성 시 이루어진다.
     *
     * <p>값은 모두 주문 항목의 결제 시점 값이다. 사용자 입력이 아니므로 잘못된 값은 호출한 코드의 버그로 보고 표준 예외를 던진다.
     *
     * @throws IllegalArgumentException orderItemId나 상품명이 없거나, 수량이 1 미만이거나, 단가가 음수면
     */
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
     * <p>취소 수량은 사용자의 취소 요청(주문 취소, 배송 상품 일부 취소)에서 온다.
     * 더 취소할 수 없는 상태에서 다시 요청할 수 있으므로 응답으로 알릴 수 있게 에러 코드로 던진다.
     *
     * @throws RestApiException 수량이 1 미만이면 {@code INVALID_CANCEL_QUANTITY},
     *                          누적 취소 수량이 주문 수량을 넘으면 {@code CANCEL_QUANTITY_EXCEEDED}
     */
    public void cancel(int cancelQuantity) {
        if (cancelQuantity <= 0) {
            throw new RestApiException(DeliveryErrorCode.INVALID_CANCEL_QUANTITY);
        }
        if (canceledQuantity + cancelQuantity > quantity) {
            throw new RestApiException(DeliveryErrorCode.CANCEL_QUANTITY_EXCEEDED);
        }
        this.canceledQuantity += cancelQuantity;
    }

    /**
     * 취소 확정을 예약한다. 예약 수량은 취소 확정 이벤트를 받을 때 취소 수량으로 옮겨진다.
     *
     * <p>shipment 상태 확인은 {@link Shipment#reserveCancel}이 한다.
     * 예약 가능한 수량은 주문 수량에서 취소된 수량과 이미 예약된 수량을 뺀 값이다.
     *
     * @throws RestApiException 수량이 1 미만이면 {@code INVALID_CANCEL_QUANTITY},
     *                          예약 가능한 수량을 넘으면 {@code CANCEL_QUANTITY_EXCEEDED}
     */
    void reserveCancel(int reserveQuantity) {
        if (reserveQuantity <= 0) {
            throw new RestApiException(DeliveryErrorCode.INVALID_CANCEL_QUANTITY);
        }
        if (canceledQuantity + reservedCancelQuantity + reserveQuantity > quantity) {
            throw new RestApiException(DeliveryErrorCode.CANCEL_QUANTITY_EXCEEDED);
        }
        this.reservedCancelQuantity += reserveQuantity;
    }

    /** 취소 확정 예약이 걸려 있는지 여부. 예약이 있으면 송장을 등록할 수 없다. */
    public boolean hasReservedCancel() {
        return reservedCancelQuantity > 0;
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
