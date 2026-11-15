package com.ok.wallet.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.wallet.WalletSchema;
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
 * 지갑이 처리한 환불 반환 요청의 결과. 환불 하나에 한 건만 남고 바뀌지 않는다.
 * 같은 환불이 다시 오면 이 기록의 결과를 그대로 다시 보내고 돈을 다시 주지 않는다. 거절도 남겨 다시 와도 같은 답을 한다.
 * 처리 시각은 생성 시각(createdAt)으로 본다.
 * 기록은 {@code WalletOrderRefundRepository.insertIfAbsent}로만 넣는다. 주문 결제 처리 기록({@link WalletOrderPayment})과 같은 이유다.
 */
@Entity
@Table(name = "wallet_order_refund", catalog = WalletSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletOrderRefund extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long refundId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long buyerId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletOrderRefundStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private WalletOrderRefundFailureReason failureReason;

    public boolean isCompleted() {
        return status == WalletOrderRefundStatus.COMPLETED;
    }
}
