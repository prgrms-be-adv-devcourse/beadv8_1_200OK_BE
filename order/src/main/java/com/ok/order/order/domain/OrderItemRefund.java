package com.ok.order.order.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.order.OrderSchema;
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

import java.time.LocalDateTime;

/**
 * 환불 모듈이 승인 직후 알려 준 주문 상품 취소 반영 기록이다. 환불 한 건이 한 줄이다.
 * 알림을 받은 즉시 확정된 환불로 보고 구매확정 금액에서 뺀다. 승인 뒤 단계가 끝내 실패하면 환불이 되돌리기를 요청하고(#604),
 * 그때는 줄을 지우지 않고 되돌린 시각만 남겨 합계에서 뺀다. 같은 요청이 다시 오거나 늦게 온 기록 요청을 가르려면 흔적이 필요하다.
 */
@Entity
@Table(name = "order_item_refund", catalog = OrderSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemRefund extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    // 환불 모듈 환불 ID
    @Column(nullable = false, unique = true)
    private Long refundId;

    @Column(nullable = false)
    private int quantity;

    // 환불 금액에서 뺀 배송비 (원)
    @Column(nullable = false)
    private long deductedShippingFee;

    // 취소 기록을 되돌린 시각. 비어 있으면 유효한 취소 기록이다(#604)
    @Column
    private LocalDateTime revertedAt;

    // 값은 환불 모듈이 계산해 넘긴 것이라 어긋나면 호출하는 쪽의 버그다(요청 형식은 내부 API 요청 DTO가 먼저 검증한다)
    static OrderItemRefund create(OrderItem orderItem, Long refundId, int quantity, long deductedShippingFee) {
        if (refundId == null) {
            throw new IllegalArgumentException("refundId는 필수입니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("환불 수량은 1 이상이어야 합니다. quantity=" + quantity);
        }
        if (deductedShippingFee < 0) {
            throw new IllegalArgumentException("뺀 배송비는 0 이상이어야 합니다. deductedShippingFee=" + deductedShippingFee);
        }
        OrderItemRefund refund = new OrderItemRefund();
        refund.orderItem = orderItem;
        refund.refundId = refundId;
        refund.quantity = quantity;
        refund.deductedShippingFee = deductedShippingFee;
        return refund;
    }

    // 되돌린 기록이면 환불 수량·뺀 배송비 합계에서 뺀다
    boolean isReverted() {
        return revertedAt != null;
    }

    // 처음 되돌린 시각을 남긴다. 이미 되돌렸으면 그대로 둔다
    void revert(LocalDateTime now) {
        if (revertedAt == null) {
            this.revertedAt = now;
        }
    }

    // 같은 환불 ID로 다시 온 요청이 처음과 같은 내용인지
    boolean hasSameContent(int quantity, long deductedShippingFee) {
        return this.quantity == quantity && this.deductedShippingFee == deductedShippingFee;
    }
}
