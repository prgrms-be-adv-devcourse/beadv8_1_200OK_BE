package com.ok.order.order.domain;

import com.ok.common.exception.RestApiException;
import com.ok.order.OrderSchema;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.order.order.exception.OrderErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
@Table(name = "order_item", catalog = OrderSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_group_id", nullable = false)
    private OrderSellerGroup sellerGroup;

    // 상품 모듈 상품 ID
    @Column(nullable = false)
    private Long productId;

    // 상품 모듈 옵션 ID
    @Column(nullable = false)
    private Long productOptionId;

    // 주문 시점 상품명
    @Column(nullable = false, length = 100)
    private String productName;

    // 주문 시점 옵션명
    @Column(nullable = false, length = 100)
    private String productOptionName;

    // 주문 시점 판매가
    @Column(nullable = false)
    private long unitPrice;

    // 수량
    @Column(nullable = false)
    private int quantity;

    // 판매가 × 수량
    @Column(nullable = false)
    private long itemAmount;

    // 주문 상품 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderItemStatus status;

    // 배송 완료 시각
    @Column
    private LocalDateTime deliveredAt;

    // 구매 확정 시각
    @Column
    private LocalDateTime confirmedAt;

    // 환불 취소 반영 기록 (환불 한 건 = 한 줄)
    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL)
    private List<OrderItemRefund> refunds = new ArrayList<>();

    static OrderItem create(OrderSellerGroup sellerGroup, OrderLine line) {
        OrderItem item = new OrderItem();
        item.sellerGroup = sellerGroup;
        item.productId = line.productId();
        item.productOptionId = line.productOptionId();
        item.productName = line.productName();
        item.productOptionName = line.productOptionName();
        item.unitPrice = line.unitPrice();
        item.quantity = line.quantity();
        item.itemAmount = line.unitPrice() * line.quantity();
        item.status = OrderItemStatus.PAYMENT_PENDING;
        return item;
    }

    // 결제 완료 -> 배송 준비
    void markPreparing() {
        this.status = OrderItemStatus.PREPARING;
    }

    // 결제 실패
    void markPaymentFailed() {
        this.status = OrderItemStatus.PAYMENT_FAILED;
    }

    // 취소
    void cancel() {
        this.status = OrderItemStatus.CANCELED;
    }

    // 배송 시작. 모두 환불된 상품은 보낼 것이 없어 배송 준비로 둔다.
    // 환불 기록은 상태를 바꾸지 않아(#28), 막지 않으면 같은 묶음 송장이 등록될 때 함께 배송 중이 된다(#658)
    boolean markShipping() {
        if (status != OrderItemStatus.PREPARING || isFullyRefunded()) {
            return false;
        }
        this.status = OrderItemStatus.SHIPPING;
        return true;
    }

    // 배송 완료 (배송 준비, 배송 중 상태에서만 변경 가능). 배송 시작 없이 완료가 먼저 와도
    // 모두 환불된 상품은 배송 시작과 같은 이유로 배송 준비로 둔다(#658)
    boolean markDelivered(LocalDateTime deliveredAt) {
        if (status != OrderItemStatus.PREPARING && status != OrderItemStatus.SHIPPING) {
            return false;
        }
        if (isFullyRefunded()) {
            return false;
        }
        this.status = OrderItemStatus.DELIVERED;
        this.deliveredAt = deliveredAt;
        return true;
    }

    // 구매 확정
    public boolean confirmPurchase(LocalDateTime now) {
        if (status != OrderItemStatus.DELIVERED) {
            return false;
        }
        this.status = OrderItemStatus.PURCHASE_CONFIRMED;
        this.confirmedAt = now;
        return true;
    }

    // 배송 완료 시각이 기준 시각 이전인지
    boolean isDeliveredBefore(LocalDateTime deadline) {
        return deliveredAt != null && !deliveredAt.isAfter(deadline);
    }

    /**
     * 환불 모듈이 승인 직후 알려 준 취소를 기록한다. 주문 상품 상태는 바꾸지 않고 수량만 남긴다.
     * 같은 환불 ID가 같은 내용으로 다시 오면 기록하지 않고 {@code false}를 돌려준다(환불은 같은 요청을 다시 보낼 수 있다).
     * 배송 잠금이 없어 승인 뒤 출고를 알아챌 수 있는 곳이 주문뿐이라, 출고 전(배송 준비)이 아니면 거절해 실패로 드러나게 한다.
     *
     * @return 이번에 새로 기록했으면 {@code true}
     * @throws RestApiException 같은 환불 ID로 내용이 다르면 {@code REFUND_ID_CONFLICT}, 이미 되돌린 환불이면 {@code REFUND_ALREADY_REVERTED},
     *                          출고 전이 아니면 {@code REFUND_NOT_ALLOWED_STATUS},
     *                          환불 수량 합계가 주문 수량을 넘으면 {@code REFUND_QUANTITY_EXCEEDED}
     */
    public boolean recordRefund(Long refundId, int refundQuantity, long deductedShippingFee) {
        Optional<OrderItemRefund> existing = findRefund(refundId);
        if (existing.isPresent()) {
            // 되돌린 뒤 늦게 온 기록 요청이다. 되살리면 실패로 끝난 환불의 수량이 다시 빠진다(#604)
            if (existing.get().isReverted()) {
                throw new RestApiException(OrderErrorCode.REFUND_ALREADY_REVERTED);
            }
            if (existing.get().hasSameContent(refundQuantity, deductedShippingFee)) {
                return false;
            }
            throw new RestApiException(OrderErrorCode.REFUND_ID_CONFLICT);
        }
        if (status != OrderItemStatus.PREPARING) {
            throw new RestApiException(OrderErrorCode.REFUND_NOT_ALLOWED_STATUS);
        }
        if (refundedQuantity() + refundQuantity > quantity) {
            throw new RestApiException(OrderErrorCode.REFUND_QUANTITY_EXCEEDED);
        }
        refunds.add(OrderItemRefund.create(this, refundId, refundQuantity, deductedShippingFee));
        return true;
    }

    /**
     * 환불이 요청한 취소 기록 되돌리기를 판단하고, 되돌릴 수 있으면 되돌린다(#604).
     * 줄은 지우지 않고 되돌린 시각만 남겨 합계에서 뺀다. 주문 상품 상태는 바꾸지 않는다.
     * 출고 전(배송 준비)이고 같은 판매자 묶음도 출고되지 않았을 때만 되돌린다. 모두 환불된 상품은 묶음이 출고돼도 배송 준비로 남는데,
     * 그 뒤에 되돌리면 배송 시작이 다시 오지 않아 상품이 배송 준비에 머물고 구매확정·정산도 되지 않기 때문이다.
     *
     * @param quantity 환불이 되돌리려는 취소 수량. 남긴 기록과 다르면 기록이 어긋난 것이라 되돌리지 않는다
     */
    public RefundRevertOutcome revertRefund(Long refundId, int quantity, LocalDateTime now) {
        Optional<OrderItemRefund> found = findRefund(refundId);
        if (found.isEmpty()) {
            return RefundRevertOutcome.REFUND_NOT_FOUND;
        }
        OrderItemRefund refund = found.get();
        if (refund.isReverted()) {
            return RefundRevertOutcome.ALREADY_REVERTED;
        }
        if (refund.getQuantity() != quantity) {
            return RefundRevertOutcome.QUANTITY_MISMATCH;
        }
        if (status == OrderItemStatus.PURCHASE_CONFIRMED) {
            return RefundRevertOutcome.ALREADY_CONFIRMED;
        }
        if (status != OrderItemStatus.PREPARING || sellerGroup.hasShipped()) {
            return RefundRevertOutcome.ALREADY_SHIPPED;
        }
        refund.revert(now);
        return RefundRevertOutcome.REVERTED;
    }

    // 환불 반영된 수량 합계. 되돌린 기록은 뺀다(#604)
    public int refundedQuantity() {
        return refunds.stream().filter(refund -> !refund.isReverted()).mapToInt(OrderItemRefund::getQuantity).sum();
    }

    // 구매확정으로 정산할 상품 금액. 환불 반영된 수량을 뺀다
    public long confirmedItemAmount() {
        return unitPrice * (quantity - refundedQuantity());
    }

    // 모두 환불된 상품이면 구매확정해도 정산할 금액이 없다
    public boolean isFullyRefunded() {
        return refundedQuantity() >= quantity;
    }

    // 이 주문 상품의 환불에서 뺀 배송비 합계. 되돌린 기록은 뺀다(#604)
    long deductedShippingFee() {
        return refunds.stream().filter(refund -> !refund.isReverted()).mapToLong(OrderItemRefund::getDeductedShippingFee).sum();
    }

    private Optional<OrderItemRefund> findRefund(Long refundId) {
        return refunds.stream().filter(refund -> refund.getRefundId().equals(refundId)).findFirst();
    }
}