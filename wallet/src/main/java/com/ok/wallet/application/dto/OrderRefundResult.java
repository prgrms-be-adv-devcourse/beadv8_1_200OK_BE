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

    /**
     * 돌려준 뒤 되돌리기로 다시 가져온 환불에 같은 반환 요청이 다시 온 결과를 만든다.
     * 처음 결과(돌려줌)를 그대로 보내면 환불은 돈이 구매자에게 있다고 보게 되어, 되돌렸다는 거절로 답한다.
     */
    public static OrderRefundResult revertedAfterRefund(WalletOrderRefund refund) {
        return new OrderRefundResult(refund.getRefundId(), refund.getOrderId(), refund.getBuyerId(), refund.getAmount(),
                WalletOrderRefundStatus.FAILED, WalletOrderRefundFailureReason.REFUND_REVERTED, true);
    }

    public boolean completed() {
        return status == WalletOrderRefundStatus.COMPLETED;
    }
}
