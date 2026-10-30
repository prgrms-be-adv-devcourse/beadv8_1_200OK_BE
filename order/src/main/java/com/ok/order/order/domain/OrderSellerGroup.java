package com.ok.order.order.domain;

import com.ok.order.OrderSchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "order_seller_group", catalog = OrderSchema.NAME)
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

    // 묶음 배송비: 무료배송을 적용한 뒤 구매자가 내는 기본 배송비 (도서산간 운임 제외)
    @Column(nullable = false)
    private long shippingFee;

    // 판매자 설정의 기본 배송비, 무료배송이어도 설정 값
    // 결제 완료 이벤트로 배송에 넘겨, 배송이 결제 때와 같은 규칙으로 실제 받은 배송비를 다시 계산하게 한다
    @Column(nullable = false)
    private long baseShippingFee;

    // 판매자 설정의 무료배송 기준 금액, null이면 무료배송 없음 (0은 항상 무료라 따로 표현)
    private Long freeShippingThreshold;

    // 도서산간 추가 운임, 배송 모듈이 배송비와 함께 계산한 값 (배송지를 정하기 전인 주문서에서는 0)
    @Column(nullable = false)
    private long remoteAreaFee;

    // 이 묶음의 주문 상품들
    @OneToMany(mappedBy = "sellerGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    static OrderSellerGroup create(Order order, Long sellerId, List<OrderLine> lines) {
        OrderSellerGroup group = new OrderSellerGroup();
        group.order = order;
        group.sellerId = sellerId;

        // 배송비와 도서산간 운임은 판매자 설정·지역 데이터를 가진 배송 모듈이 계산하므로, 생성 뒤 applyShippingFee로 채운다
        group.shippingFee = 0L;
        group.baseShippingFee = 0L;

        group.remoteAreaFee = 0L;

        // 이 판매자의 상품마다 주문 상품 만들기
        for (OrderLine line : lines) {
            OrderItem item = OrderItem.create(group, line);
            group.items.add(item);
        }

        return group;
    }

    void applyShippingFee(SellerShippingFee fee) {
        this.baseShippingFee = fee.baseShippingFee();
        this.freeShippingThreshold = fee.freeShippingThreshold();
        this.shippingFee = fee.shippingFee();
        this.remoteAreaFee = fee.remoteAreaFee();
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