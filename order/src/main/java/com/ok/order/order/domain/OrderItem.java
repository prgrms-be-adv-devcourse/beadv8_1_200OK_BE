package com.ok.order.order.domain;

import com.ok.order.OrderSchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    // 배송 시작
    boolean markShipping() {
        if (status != OrderItemStatus.PREPARING) {
            return false;
        }
        this.status = OrderItemStatus.SHIPPING;
        return true;
    }

    // 배송 완료 (배송 준비, 배송 중 상태에서만 변경 가능)
    boolean markDelivered(LocalDateTime deliveredAt) {
        if (status != OrderItemStatus.PREPARING && status != OrderItemStatus.SHIPPING) {
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
}