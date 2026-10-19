package com.ok.order.order.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_item")
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

}