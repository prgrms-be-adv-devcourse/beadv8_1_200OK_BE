package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.delivery.exception.DeliveryErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

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
public class Shipment extends BaseTime {

    // 송장번호 형식 규칙은 여기 한 곳에서 관리한다. 택배사 공통 규칙(숫자 10~13자리)이라 택배사별로 나누지 않았다
    private static final Pattern TRACKING_NUMBER_PATTERN = Pattern.compile("\\d{10,13}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShipmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", length = 30)
    private Carrier carrier;

    @Column(name = "tracking_number", length = 13)
    private String trackingNumber;

    @Embedded
    private Address address;

    @Column(name = "region_group", nullable = false, length = 30)
    private String regionGroup;

    // 무료배송이어도 판매자 설정의 기본 배송비를 그대로 둔다. 부분 취소처럼 결제 당시 규칙으로 다시 계산할 때 쓴다
    @Column(name = "base_shipping_fee", nullable = false)
    private int baseShippingFee;

    // 구매자가 이 송장에 실제로 낸 배송비(무료배송 적용 후 기본 배송비 + 추가 운임). 규칙(위 값들)과 따로 결과를 남긴다
    @Column(name = "charged_shipping_fee", nullable = false)
    private int chargedShippingFee;

    @Column(name = "region_surcharge", nullable = false)
    private int regionSurcharge;

    // null이면 결제 시점의 판매자 설정에 무료배송이 없었다 (SellerShippingSetting)
    @Column(name = "free_shipping_threshold")
    private Integer freeShippingThreshold;

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
     *
     * <p>값은 모두 주문·결제와 운임 정책이 계산해 넘긴다. 사용자 입력이 아니므로 잘못된 값은 호출한 코드의 버그로 보고 표준 예외를 던진다.
     * 사용자가 입력하는 배송지는 {@link Address#of}가 먼저 검증한다.
     *
     * @throws IllegalArgumentException 필수값이 없거나, 배송비가 음수거나, 도착 예정일 시작이 끝보다 늦거나, 상품 항목이 없으면
     */
    @Builder
    private Shipment(
            Long orderId,
            Long sellerId,
            Address address,
            String regionGroup,
            int baseShippingFee,
            int chargedShippingFee,
            int regionSurcharge,
            Integer freeShippingThreshold,
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
        if (baseShippingFee < 0 || chargedShippingFee < 0 || regionSurcharge < 0
                || (freeShippingThreshold != null && freeShippingThreshold < 0)) {
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
        this.chargedShippingFee = chargedShippingFee;
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
     * <p>송장번호 중복은 DB UNIQUE 제약({@code carrier, tracking_number})이 막는다. 송장번호는 택배사마다 따로 발급하므로
     * 택배사가 다르면 같은 번호도 허용한다. 대기 중인 배송지 변경 요청의 자동 거절은 호출하는 서비스가 한다.
     * 취소 확정 예약과 경합하지 않도록 서비스는 shipment 행 잠금 아래에서 호출해야 한다.
     *
     * @throws RestApiException {@code PREPARING}이 아니면 {@code INVALID_SHIPMENT_STATE},
     *                          택배사나 송장번호가 비어 있으면 {@code MISSING_CARRIER}, {@code MISSING_TRACKING_NUMBER},
     *                          송장번호가 숫자 10~13자리가 아니면 {@code INVALID_TRACKING_NUMBER},
     *                          취소 확정 예약된 항목이 있으면 {@code CANCELLATION_RESERVED}
     */
    public void dispatch(Carrier carrier, String trackingNumber, LocalDateTime now) {
        requireStatus(ShipmentStatus.PREPARING);
        if (carrier == null) {
            throw new RestApiException(DeliveryErrorCode.MISSING_CARRIER);
        }
        if (isBlank(trackingNumber)) {
            throw new RestApiException(DeliveryErrorCode.MISSING_TRACKING_NUMBER);
        }
        if (!TRACKING_NUMBER_PATTERN.matcher(trackingNumber).matches()) {
            throw new RestApiException(DeliveryErrorCode.INVALID_TRACKING_NUMBER);
        }
        if (items.stream().anyMatch(ShipmentItem::hasReservedCancel)) {
            throw new RestApiException(DeliveryErrorCode.CANCELLATION_RESERVED);
        }
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.status = ShipmentStatus.SHIPPING;
        this.shippedAt = now;
    }

    /**
     * 항목의 취소 확정을 예약한다. 송장 발행 전({@code PREPARING})에만 가능하다.
     * 예약이 남아 있는 동안에는 {@link #dispatch}가 거절되므로, 서비스는 shipment 행 잠금 아래에서 호출해야 한다.
     *
     * @param orderItemId 환불이 넘기는 주문 상품 ID. 이 shipment의 항목이어야 한다
     * @throws RestApiException {@code PREPARING}이 아니면 {@code INVALID_SHIPMENT_STATE},
     *                          수량 오류는 {@link ShipmentItem#reserveCancel}을 따른다
     * @throws IllegalArgumentException 이 shipment의 항목이 아니면
     */
    public void reserveCancel(Long orderItemId, int quantity) {
        requireStatus(ShipmentStatus.PREPARING);
        ShipmentItem item = items.stream()
                .filter(it -> it.getOrderItemId().equals(orderItemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("shipment에 없는 주문 상품입니다. orderItemId=" + orderItemId));
        item.reserveCancel(quantity);
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
     * <p>추가 운임 안내 금액은 요청 배송지의 추가 운임에서 결제 때 낸 추가 운임을 뺀 값이다. 결제 때 낸 운임은 이미 받았으므로 차액만 안내한다.
     * 추가 운임이 줄어도 돌려주지 않으므로 0원이다.
     *
     * @param requestedRegionSurcharge 요청 배송지의 지역 판정 결과에 따른 추가 운임
     * @throws RestApiException {@code PREPARING}이 아니면 {@code ADDRESS_CHANGE_NOT_ALLOWED}
     * @throws IllegalArgumentException 요청 배송지의 추가 운임이 음수면
     */
    public ShipmentAddressChangeRequest requestAddressChange(Address requestedAddress, int requestedRegionSurcharge, LocalDateTime now) {
        requireAddressChangeable();
        if (requestedRegionSurcharge < 0) {
            throw new IllegalArgumentException("추가 운임은 0 이상이어야 합니다.");
        }
        int additionalFeeNotice = Math.max(0, requestedRegionSurcharge - this.regionSurcharge);
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
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
