package com.ok.order.order.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "order_seller_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderSellerGroup extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // 판매자 회원 ID
    @Column(nullable = false)
    private Long sellerId;

    // 묶음 배송비
    @Column(nullable = false)
    private long shippingFee;

    // 도서산간 추가 운임, 주문서 발행 시 배송 모듈이 계산한 값
    @Column(nullable = false)
    private long remoteAreaFee;

    // 이 묶음의 주문 상품들
    @OneToMany(mappedBy = "sellerGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    static OrderSellerGroup create(Order order, Long sellerId, List<OrderLine> lines) {
        OrderSellerGroup group = new OrderSellerGroup();
        group.order = order;
        group.sellerId = sellerId;

        // TODO 배송비 기준 협의 후 계산 로직 구현
        group.shippingFee = 0L;

        group.remoteAreaFee = 0L;

        // 이 판매자의 상품마다 주문 상품 만들기
        for (OrderLine line : lines) {
            OrderItem item = OrderItem.create(group, line);
            group.items.add(item);
        }

        return group;
    }

    void applyRemoteAreaFee(long remoteAreaFee) {
        this.remoteAreaFee = remoteAreaFee;
    }

    long productAmount() {
        long sum = 0;
        for (OrderItem item : items) {
            sum += item.getItemAmount();
        }
        return sum;
    }

    // 이 판매자 묶음의 상품들을 '배송 중'으로 변경
    int markShipping() {
        int changed = 0;
        for (OrderItem item : items) {
            if (item.markShipping()) {
                changed++;
            }
        }
        return changed;
    }

    // 이 판매자 묶음의 상품들을 '배송 완료'으로 변경
    int markDelivered(LocalDateTime deliveredAt) {
        int changed = 0;
        for (OrderItem item : items) {
            if (item.markDelivered(deliveredAt)) {
                changed++;
            }
        }
        return changed;
    }
}