package com.ok.wallet.application.dto;

import com.ok.wallet.domain.WalletOrderRefund;
import com.ok.wallet.domain.WalletOrderRefundFailureReason;
import com.ok.wallet.domain.WalletOrderRefundStatus;

/**
 * 환불 반환 요청을 처리한 결과. 환불에 보낼 결과 메시지의 값이 된다.
 * 이미 처리한 환불이면 처음 기록의 값을 담는다. 같은 환불이 금액을 바꿔 다시 와도 처음 금액이 나가, 환불이 어긋남을 알아챈다.
 *
 * @param failureReason    거절했을 때만 있다
 * @param alreadyProcessed 이번 요청 전에 이미 처리한 환불이면 true. 이번에 잔액을 바꾸지 않았다는 뜻이다
 */
public record OrderRefundResult(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount,
        WalletOrderRefundStatus status,
        WalletOrderRefundFailureReason failureReason,
        boolean alreadyProcessed
) {
    public static OrderRefundResult processed(Long refundId, Long orderId, Long buyerId, long amount,
                                              WalletOrderRefundStatus status,
                                              WalletOrderRefundFailureReason failureReason) {
        return new OrderRefundResult(refundId, orderId, buyerId, amount, status, failureReason, false);
    }

    public static OrderRefundResult alreadyProcessed(WalletOrderRefund refund) {
        return new OrderRefundResult(refund.getRefundId(), refund.getOrderId(), refund.getBuyerId(), refund.getAmount(),
                refund.getStatus(), refund.getFailureReason(), true);
    }

    public boolean completed() {
        return status == WalletOrderRefundStatus.COMPLETED;
    }
}
