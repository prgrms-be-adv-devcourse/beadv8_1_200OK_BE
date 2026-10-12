package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseIdAndTime;
import com.ok.delivery.exception.DeliveryErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 구매자의 배송지 변경 요청. 요청 한 건이 한 행이고, 변경 전·후 배송지를 이력으로 남긴다.
 *
 * <p>상태는 {@code PENDING → APPROVED} 또는 {@code PENDING → REJECTED}이고, 처리된 요청은 다시 처리할 수 없다.
 * shipment와 별도 애그리거트라 shipment는 id로만 참조한다.
 */
@Entity
@Getter
@Table(name = "shipment_address_change_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentAddressChangeRequest extends BaseIdAndTime {

    /** 판매자 거절과 송장 등록 시 자동 거절에 쓰는 고정 거절 사유 */
    public static final String REJECT_REASON = "상품 준비가 완료되어 배송지 변경이 불가능합니다.";

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "recipientName", column = @Column(name = "requested_recipient_name", nullable = false, length = 50)),
            @AttributeOverride(name = "recipientPhone", column = @Column(name = "requested_recipient_phone", nullable = false, length = 11)),
            @AttributeOverride(name = "postalCode", column = @Column(name = "requested_postal_code", nullable = false, length = 5)),
            @AttributeOverride(name = "address", column = @Column(name = "requested_address", nullable = false)),
            @AttributeOverride(name = "addressDetail", column = @Column(name = "requested_address_detail", nullable = false)),
            @AttributeOverride(name = "deliveryNote", column = @Column(name = "requested_delivery_note"))
    })
    private Address requestedAddress;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "recipientName", column = @Column(name = "previous_recipient_name", nullable = false, length = 50)),
            @AttributeOverride(name = "recipientPhone", column = @Column(name = "previous_recipient_phone", nullable = false, length = 11)),
            @AttributeOverride(name = "postalCode", column = @Column(name = "previous_postal_code", nullable = false, length = 5)),
            @AttributeOverride(name = "address", column = @Column(name = "previous_address", nullable = false)),
            @AttributeOverride(name = "addressDetail", column = @Column(name = "previous_address_detail", nullable = false)),
            @AttributeOverride(name = "deliveryNote", column = @Column(name = "previous_delivery_note"))
    })
    private Address previousAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AddressChangeStatus status;

    /** 변경으로 늘어나는 추가 운임 안내 금액. 시스템에서 결제하지 않고 판매자가 구매자에게 직접 받는다 */
    @Column(name = "additional_fee_notice", nullable = false)
    private int additionalFeeNotice;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    private ShipmentAddressChangeRequest(
            Long shipmentId,
            Address requestedAddress,
            Address previousAddress,
            int additionalFeeNotice,
            LocalDateTime requestedAt
    ) {
        if (shipmentId == null) {
            throw new IllegalArgumentException("shipmentId는 필수입니다.");
        }
        if (requestedAddress == null || previousAddress == null) {
            throw new IllegalArgumentException("배송지는 필수입니다.");
        }
        if (additionalFeeNotice < 0) {
            throw new IllegalArgumentException("추가 운임 안내 금액은 0 이상이어야 합니다.");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("요청 시각은 필수입니다.");
        }
        this.shipmentId = shipmentId;
        this.requestedAddress = requestedAddress;
        this.previousAddress = previousAddress;
        this.status = AddressChangeStatus.PENDING;
        this.additionalFeeNotice = additionalFeeNotice;
        this.requestedAt = requestedAt;
    }

    /** {@link Shipment#requestAddressChange}에서만 호출한다. 상태는 {@code PENDING}으로 시작한다. */
    static ShipmentAddressChangeRequest create(
            Long shipmentId,
            Address requestedAddress,
            Address previousAddress,
            int additionalFeeNotice,
            LocalDateTime requestedAt
    ) {
        return new ShipmentAddressChangeRequest(shipmentId, requestedAddress, previousAddress, additionalFeeNotice, requestedAt);
    }

    /**
     * 판매자가 요청을 승인한다. {@code PENDING → APPROVED}
     *
     * <p>shipment 배송지 변경은 서비스가 같은 트랜잭션에서 {@link Shipment#changeAddress}로 한다.
     *
     * @throws RestApiException 이미 처리된 요청이면 {@code ADDRESS_CHANGE_ALREADY_PROCESSED}
     */
    public void approve(LocalDateTime now) {
        requirePending();
        this.status = AddressChangeStatus.APPROVED;
        this.processedAt = now;
    }

    /**
     * 요청을 거절하고 고정 거절 사유를 남긴다. {@code PENDING → REJECTED}
     *
     * <p>판매자 거절과 송장 등록 시 자동 거절이 같은 메서드를 쓴다.
     *
     * @throws RestApiException 이미 처리된 요청이면 {@code ADDRESS_CHANGE_ALREADY_PROCESSED}
     */
    public void reject(LocalDateTime now) {
        requirePending();
        this.status = AddressChangeStatus.REJECTED;
        this.rejectReason = REJECT_REASON;
        this.processedAt = now;
    }

    private void requirePending() {
        if (this.status != AddressChangeStatus.PENDING) {
            throw new RestApiException(DeliveryErrorCode.ADDRESS_CHANGE_ALREADY_PROCESSED);
        }
    }
}
