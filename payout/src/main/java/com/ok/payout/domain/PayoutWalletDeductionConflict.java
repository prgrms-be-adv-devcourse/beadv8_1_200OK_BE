package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.PayoutSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import static jakarta.persistence.GenerationType.IDENTITY;

// 지갑 차감 금액 충돌 기록 (#391)
// 이미 차감 기록(PayoutWalletDeduction)이 있는 주문에, 저장된 금액과 다른 금액의 지갑 차감 이벤트가 들어온 사실을 남김
// 차감 기록은 주문당 1건이고 바꾸지 않음(먼저 온 값이 기준). 다른 값이 왔다는 사실만 여기 남겨, 초과 청구가 났을 때 원인을 찾는 근거로 씀
// 같은 (주문, 받은 금액) 충돌은 1건만 둠 (Kafka 재전달로 같은 이벤트가 여러 번 와도 늘어나지 않게, UK)
@Entity
@Immutable
@Table(name = "payout_wallet_deduction_conflict", catalog = PayoutSchema.NAME,
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payout_wallet_deduction_conflict_order_id_received_amount",
                columnNames = {"order_id", "received_amount"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutWalletDeductionConflict extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 다른 모듈(order)의 ID라 숫자만 둠 (PayoutWalletDeduction과 같음)
    @Column(nullable = false)
    private Long orderId;

    // 충돌을 기록할 때 저장되어 있던 차감 금액 (원). 차감 기록을 다시 조인하지 않고 이 행만 보고 비교할 수 있게 복사해 둠
    @Column(nullable = false)
    private Long storedAmount;

    // 새로 받은 이벤트의 차감 금액 (원)
    @Column(nullable = false)
    private Long receivedAmount;

    // 값 검증은 하지 않음: 받은 금액은 입구(PayoutInboundEventValidator)가 확인했고, 저장된 금액은 이미 저장된 차감 기록 값임
    public static PayoutWalletDeductionConflict create(Long orderId, Long storedAmount, Long receivedAmount) {
        PayoutWalletDeductionConflict conflict = new PayoutWalletDeductionConflict();
        conflict.orderId = orderId;
        conflict.storedAmount = storedAmount;
        conflict.receivedAmount = receivedAmount;
        return conflict;
    }
}
