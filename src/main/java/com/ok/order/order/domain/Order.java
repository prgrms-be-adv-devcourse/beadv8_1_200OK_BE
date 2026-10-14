package com.ok.order.order.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 구매자 ID
    @Column(nullable = false)
    private Long buyerId;

    // 주문 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    // 수령인 이름
    @Column(nullable = false, length = 50)
    private String recipientName;

    // 수령인 연락처, 하이픈 포함 형식(000-0000-0000)
    @Column(nullable = false, length = 20)
    private String recipientPhone;

    // 우편번호 (숫자 5자리)
    @Column(nullable = false, length = 5)
    private String zipCode;

    // 기본 주소(도로명 또는 지번)
    @Column(nullable = false, length = 200)
    private String address;

    // 상세 주소(동, 호수 등)
    @Column(length = 100)
    private String addressDetail;

    // 상품 금액 합계
    @Column(nullable = false)
    private long totalProductAmount;

    // 배송비 합계
    @Column(nullable = false)
    private long totalShippingFee;

    // 도서산간 추가 운임 합계
    @Column(nullable = false)
    private long totalRemoteAreaFee;

    // 최종 결제 금액
    @Column(nullable = false)
    private long totalAmount;
}