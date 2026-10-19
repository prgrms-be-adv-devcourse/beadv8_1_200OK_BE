package com.ok.wallet.domain;

import com.ok.common.jpa.entity.BaseTime;
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

/**
 * 지갑이 처리한 주문 결제 요청의 결과. 주문 하나에 한 건만 남고 바뀌지 않는다.
 * 주문 상태는 주문 모듈이 관리하고, 이 기록은 같은 결제 요청이 재전달되어도 예치금을 다시 차감하지 않기 위한 지갑의 처리 기록이다.
 * 잔액이 실제로 바뀐 것만 남는 원장으로는 실패를 기억할 수 없어 따로 둔다.
 * 처리 시각은 생성 시각(createdAt)으로 본다.
 */
@Entity
@Table(name = "wallet_order_payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletOrderPayment extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false)
    private Long buyerId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletOrderPaymentStatus status;

    private WalletOrderPayment(Long orderId, Long buyerId, long amount, WalletOrderPaymentStatus status) {
        this.orderId = orderId;
        this.buyerId = buyerId;
        this.amount = amount;
        this.status = status;
    }

    public static WalletOrderPayment paid(Long orderId, Long buyerId, long amount) {
        return new WalletOrderPayment(orderId, buyerId, amount, WalletOrderPaymentStatus.PAID);
    }

    public static WalletOrderPayment failed(Long orderId, Long buyerId, long amount) {
        return new WalletOrderPayment(orderId, buyerId, amount, WalletOrderPaymentStatus.FAILED);
    }

    public boolean isPaid() {
        return status == WalletOrderPaymentStatus.PAID;
    }
}
