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
 * 지갑이 처리한 환불 되돌리기 요청의 결과. 환불 하나에 한 건만 남고 바뀌지 않는다.
 * 같은 되돌리기가 다시 오면 이 기록의 결과를 그대로 다시 보내고 돈을 다시 가져오지 않는다.
 * 구매자와 금액은 반환 처리 기록({@link WalletOrderRefund})의 값이다. 요청 값을 쓰면 다른 구매자로 잘못 돌려준 반환을 바로잡지 못한다.
 * 기록은 {@code WalletOrderRefundRevertRepository.insertIfAbsent}로만 넣는다. 반환 처리 기록과 같은 이유다.
 */
@Entity
@Table(name = "wallet_order_refund_revert", catalog = WalletSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletOrderRefundRevert extends BaseTime {
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
    private WalletOrderRefundRevertStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private WalletOrderRefundRevertFailureReason failureReason;

    public boolean isReverted() {
        return status == WalletOrderRefundRevertStatus.REVERTED;
    }
}
