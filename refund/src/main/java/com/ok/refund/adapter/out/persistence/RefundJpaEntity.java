package com.ok.refund.adapter.out.persistence;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.refund.RefundSchema;
import com.ok.refund.domain.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * {@code refund} 테이블 매핑. 도메인 {@link com.ok.refund.domain.Refund}와 칸이 같지만 비즈니스 규칙은 두지 않는다(#481).
 * 칸 이름·길이·enum 저장 방식은 도메인을 분리하기 전 매핑과 같아야 한다. {@code ddl-auto=validate}가 마이그레이션과 맞는지 검사한다.
 * 값은 {@link RefundMapper}만 채운다. 값을 넣는 생성자와 메서드를 package-private로 둬 어댑터 밖에서 엔티티를 고치지 못하게 한다.
 * 칸마다 왜 NULL을 허용하는지 같은 설명은 도메인 쪽 주석에 있다.
 */
@Entity
@Getter
@Table(name = "refund", catalog = RefundSchema.NAME)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundJpaEntity extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "option_name", nullable = false)
    private String optionName;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "item_amount", nullable = false)
    private long itemAmount;

    @Column(name = "shipping_fee_amount", nullable = false)
    private long shippingFeeAmount;

    @Column(name = "remote_area_fee_amount", nullable = false)
    private long remoteAreaFeeAmount;

    @Column(name = "deducted_shipping_fee_amount", nullable = false)
    private long deductedShippingFeeAmount;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "active_order_item_id")
    private Long activeOrderItemId;

    // 새 행 저장용. ID와 생성·수정 시각은 DB(IDENTITY)와 감사 리스너가 채우므로 받지 않는다.
    // 바뀌는 칸은 changeMutableState로 따로 채운다(매퍼가 새 행과 수정에 같은 규칙을 쓰게 하려고)
    RefundJpaEntity(
            Long orderId,
            Long orderItemId,
            int quantity,
            Long buyerId,
            Long sellerId,
            Long productOptionId,
            String productName,
            String optionName,
            long unitPrice,
            long itemAmount,
            String cancelReason
    ) {
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.quantity = quantity;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.productOptionId = productOptionId;
        this.productName = productName;
        this.optionName = optionName;
        this.unitPrice = unitPrice;
        this.itemAmount = itemAmount;
        this.cancelReason = cancelReason;
    }

    // 요청 뒤 바뀔 수 있는 칸만 받는다. 주문·상품·금액 칸은 요청 때 정해지고 바뀌지 않아,
    // 여기서 받지 않는 것으로 수정 때 실수로 덮어쓰는 일을 막는다
    void changeMutableState(
            RefundStatus status,
            String rejectReason,
            LocalDateTime decidedAt,
            LocalDateTime completedAt,
            Long activeOrderItemId,
            long shippingFeeAmount,
            long remoteAreaFeeAmount,
            long deductedShippingFeeAmount
    ) {
        this.status = status;
        this.rejectReason = rejectReason;
        this.decidedAt = decidedAt;
        this.completedAt = completedAt;
        this.activeOrderItemId = activeOrderItemId;
        this.shippingFeeAmount = shippingFeeAmount;
        this.remoteAreaFeeAmount = remoteAreaFeeAmount;
        this.deductedShippingFeeAmount = deductedShippingFeeAmount;
    }
}
