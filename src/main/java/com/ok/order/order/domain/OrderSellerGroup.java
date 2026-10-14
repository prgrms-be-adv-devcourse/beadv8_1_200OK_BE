package com.ok.order.order.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    @OneToMany(mappedBy = "sellerGroup")
    private List<OrderItem> items = new ArrayList<>();
}