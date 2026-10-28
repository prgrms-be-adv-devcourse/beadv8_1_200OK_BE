package com.ok.payout.domain;

import com.ok.payout.PayoutSchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

import static jakarta.persistence.GenerationType.IDENTITY;

// 지갑 차감 기록. 지갑이 주문 결제 금액을 구매자 예치금에서 차감했다는 이벤트를 정산이 받아 남긴 것
// 정산 교차 검증(#67)에서 정산후보와 비교할 원천 데이터라 수정·삭제하지 않음 (주문당 1건, UK(order_id))
@Entity
@Immutable
@Table(name = "payout_wallet_deduction", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutWalletDeduction extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false)
    private Long buyerId;

    // 차감 금액 (원)
    @Column(nullable = false)
    private Long amount;

    // 지갑이 차감한 시각 (이벤트의 paidAt). 정산이 받은 시각(createdAt)과 다를 수 있음
    @Column(nullable = false)
    private LocalDateTime deductedAt;

    // 값 검증은 하지 않음: 이벤트(WalletPaymentCompletedEvent) 생성자가 필수값을 이미 확인함
    public static PayoutWalletDeduction create(Long orderId, Long buyerId, Long amount, LocalDateTime deductedAt) {
        PayoutWalletDeduction deduction = new PayoutWalletDeduction();
        deduction.orderId = orderId;
        deduction.buyerId = buyerId;
        deduction.amount = amount;
        deduction.deductedAt = deductedAt;
        return deduction;
    }
}
