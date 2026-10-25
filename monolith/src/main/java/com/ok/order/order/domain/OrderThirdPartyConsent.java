package com.ok.order.order.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 구매자가 판매자에게 주문 정보(수령인, 연락처, 배송지)를 제공하는 데 동의한 기록
@Entity
@Table(name = "order_third_party_consent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderThirdPartyConsent extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // 동의한 사람 (구매자 회원 ID)
    @Column(nullable = false)
    private Long buyerId;

    // 정보를 제공받는 사람 (판매자 회원 ID)
    @Column(nullable = false)
    private Long sellerId;

    static OrderThirdPartyConsent create(Order order, Long sellerId) {
        OrderThirdPartyConsent consent = new OrderThirdPartyConsent();
        consent.order = order;
        consent.buyerId = order.getBuyerId();
        consent.sellerId = sellerId;
        return consent;
    }
}