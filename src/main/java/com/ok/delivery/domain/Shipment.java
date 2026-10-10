package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseIdAndTime;
import com.ok.delivery.exception.DeliveryErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 판매자별 배송(송장) 단위. 같은 주문의 같은 판매자 상품은 한 shipment로 배송한다.
 *
 * <p>상태 전이 규칙은 이 엔티티가 가진다. 허용되지 않은 전이는 {@code INVALID_SHIPMENT_STATE}다.
 * <pre>
 * PREPARING → SHIPPING → DELIVERED → COMPLETED
 * PREPARING → CANCELLED
 * </pre>
 *
 * <p>배송비와 도착 예정일은 결제 시점 값으로 고정한다. {@code address}는 현재 배송지라
 * 배송지 변경이 승인되면 바뀌고, 주문에 확정된 배송지는 바뀌지 않는다.
 */
@Entity
@Getter
@Table(name = "shipment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseIdAndTime {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShipmentStatus status;

    @Column(name = "carrier", length = 30)
    private String carrier;

    @Column(name = "tracking_number", length = 13, unique = true)
    private String trackingNumber;

    @Embedded
    private Address address;

    @Column(name = "region_group", nullable = false, length = 30)
    private String regionGroup;

    @Column(name = "base_shipping_fee", nullable = false)
    private int baseShippingFee;

    @Column(name = "region_surcharge", nullable = false)
    private int regionSurcharge;

    @Column(name = "free_shipping_threshold", nullable = false)
    private int freeShippingThreshold;

    @Column(name = "estimated_delivery_start", nullable = false)
    private LocalDate estimatedDeliveryStart;

    @Column(name = "estimated_delivery_end", nullable = false)
    private LocalDate estimatedDeliveryEnd;

    @Column(name = "is_delayed", nullable = false)
    private boolean delayed;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.PERSIST)
    private List<ShipmentItem> items = new ArrayList<>();

    /**
     * 결제 완료 시점에 만든다. 상태는 {@code PREPARING}, 상품 항목은 1개 이상이어야 한다.
     */
    @Builder
    private Shipment(
            Long orderId,
            Long sellerId,
            Address address,
            String regionGroup,
            int baseShippingFee,
            int regionSurcharge,
            int freeShippingThreshold,
            LocalDate estimatedDeliveryStart,
            LocalDate estimatedDeliveryEnd,
            List<ShipmentItem> items
    ) {
        requireNonNull(orderId, "orderId는 필수입니다.");
        requireNonNull(sellerId, "sellerId는 필수입니다.");
        requireNonNull(address, "배송지는 필수입니다.");
        requireText(regionGroup, "지역 그룹은 필수입니다.");
        requireNonNull(estimatedDeliveryStart, "도착 예정일 시작은 필수입니다.");
        requireNonNull(estimatedDeliveryEnd, "도착 예정일 끝은 필수입니다.");
        if (baseShippingFee < 0 || regionSurcharge < 0 || freeShippingThreshold < 0) {
            throw new IllegalArgumentException("배송비 금액은 0 이상이어야 합니다.");
        }
        if (estimatedDeliveryStart.isAfter(estimatedDeliveryEnd)) {
            throw new IllegalArgumentException("도착 예정일 시작은 끝보다 늦을 수 없습니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("상품 항목은 1개 이상이어야 합니다.");
        }
        this.orderId = orderId;
        this.sellerId = sellerId;
        this.status = ShipmentStatus.PREPARING;
        this.address = address;
        this.regionGroup = regionGroup;
        this.baseShippingFee = baseShippingFee;
        this.regionSurcharge = regionSurcharge;
        this.freeShippingThreshold = freeShippingThreshold;
        this.estimatedDeliveryStart = estimatedDeliveryStart;
        this.estimatedDeliveryEnd = estimatedDeliveryEnd;
        this.delayed = false;
        for (ShipmentItem item : items) {
            item.assignTo(this);
            this.items.add(item);
        }
    }

    /** 상품 항목 목록. 수정할 수 없는 뷰를 돌려준다. */
    public List<ShipmentItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * 판매자가 송장을 등록해 출고한다. {@code PREPARING → SHIPPING}
     *
     * <p>송장번호 중복은 DB UNIQUE 제약이 막는다. 대기 중인 배송지 변경 요청의 자동 거절은 호출하는 서비스가 한다.
     *
     * @throws RestApiException {@code PREPARING}이 아니면 {@code INVALID_SHIPMENT_STATE}
     */
    public void dispatch(String carrier, String trackingNumber, LocalDateTime now) {
        requireStatus(ShipmentStatus.PREPARING);
        requireText(carrier, "택배사는 필수입니다.");
        requireText(trackingNumber, "송장번호는 필수입니다.");
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = ShipmentStatus.SHIPPING;
        this.shippedAt = now;
    }

    /**
     * 택배사의 배송 완료 알림을 반영한다. {@code SHIPPING → DELIVERED}
     *
     * @throws RestApiException {@code SHIPPING}이 아니면 {@code INVALID_SHIPMENT_STATE}
     */
    public void deliver(LocalDateTime now) {
        requireStatus(ShipmentStatus.SHIPPING);
        this.status = ShipmentStatus.DELIVERED;
        this.deliveredAt = now;
    }

    /**
     * 거래를 완료한다. {@code DELIVERED → COMPLETED}
     *
     * <p>전량 취소된 항목을 뺀 나머지 항목이 모두 구매확정되어 있어야 한다.
     *
     * @throws RestApiException {@code DELIVERED}가 아니거나 구매확정되지 않은 항목이 있으면 {@code INVALID_SHIPMENT_STATE}
     */
    public void complete(LocalDateTime now) {
        requireStatus(ShipmentStatus.DELIVERED);
        if (!isAllConfirmed()) {
            throw new RestApiException(DeliveryErrorCode.INVALID_SHIPMENT_STATE);
        }
        this.status = ShipmentStatus.COMPLETED;
        this.completedAt = now;
    }

    /**
     * 배송을 취소한다. {@code PREPARING → CANCELLED}
     *
     * <p>모든 항목이 전량 취소 확정되어 있어야 한다. 일부만 취소되면 배송은 계속된다.
     *
     * @throws RestApiException {@code PREPARING}이 아니거나 전량 취소되지 않은 항목이 있으면 {@code INVALID_SHIPMENT_STATE}
     */
    public void cancel(LocalDateTime now) {
        requireStatus(ShipmentStatus.PREPARING);
        if (!items.stream().allMatch(ShipmentItem::isFullyCanceled)) {
            throw new RestApiException(DeliveryErrorCode.INVALID_SHIPMENT_STATE);
        }
        this.status = ShipmentStatus.CANCELLED;
        this.cancelledAt = now;
    }

    /**
     * 배송지 변경 요청이 승인되면 현재 배송지를 바꾼다. 상태는 그대로다.
     *
     * <p>지역 그룹과 도착 예정일 갱신은 승인 이슈에서 추가한다.
     *
     * @throws RestApiException {@code PREPARING}이 아니면(송장 발행 후, 취소) {@code ADDRESS_CHANGE_NOT_ALLOWED}
     */
    public void changeAddress(Address newAddress) {
        requireAddressChangeable();
        requireNonNull(newAddress, "배송지는 필수입니다.");
        this.address = newAddress;
    }

    /**
     * 배송지 변경 요청을 만든다. 현재 배송지를 변경 전 배송지로 남긴다.
     *
     * <p>shipment당 {@code PENDING} 1건 확인은 다른 행을 봐야 해서 이 메서드가 하지 않는다.
     * 서비스가 shipment 행 잠금 아래에서 확인한다.
     *
     * @param additionalFeeNotice 변경으로 늘어나는 추가 운임 안내 금액. 결제하지 않고 판매자가 직접 받는다
     * @throws RestApiException {@code PREPARING}이 아니면 {@code ADDRESS_CHANGE_NOT_ALLOWED}
     */
    public ShipmentAddressChangeRequest requestAddressChange(Address requestedAddress, int additionalFeeNotice, LocalDateTime now) {
        requireAddressChangeable();
        return ShipmentAddressChangeRequest.create(getId(), requestedAddress, this.address, additionalFeeNotice, now);
    }

    private boolean isAllConfirmed() {
        List<ShipmentItem> activeItems = items.stream()
                .filter(item -> !item.isFullyCanceled())
                .toList();
        return !activeItems.isEmpty() && activeItems.stream().allMatch(ShipmentItem::isConfirmed);
    }

    private void requireStatus(ShipmentStatus expected) {
        if (this.status != expected) {
            throw new RestApiException(DeliveryErrorCode.INVALID_SHIPMENT_STATE);
        }
    }

    private void requireAddressChangeable() {
        if (this.status != ShipmentStatus.PREPARING) {
            throw new RestApiException(DeliveryErrorCode.ADDRESS_CHANGE_NOT_ALLOWED);
        }
    }

    private static void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
