package com.ok.wallet.application.dto;

import com.ok.wallet.domain.WalletOrderRefundRevert;
import com.ok.wallet.domain.WalletOrderRefundRevertFailureReason;
import com.ok.wallet.domain.WalletOrderRefundRevertStatus;

/**
 * 환불 되돌리기 요청을 처리한 결과. 환불에 보낼 결과 메시지의 값이 된다.
 * 주문·구매자·금액은 요청 값이 아니라 반환 처리 기록의 값이다. 환불은 이 값으로 지갑이 실제로 무엇을 되돌렸는지 안다.
 * 이미 처리한 되돌리기면 처음 기록의 값을 담는다.
 *
 * @param failureReason    가져오지 못했을 때만 있다
 * @param alreadyProcessed 이번 요청 전에 이미 처리한 되돌리기면 true. 이번에 잔액을 바꾸지 않았다는 뜻이다
 */
public record OrderRefundRevertResult(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount,
        WalletOrderRefundRevertStatus status,
        WalletOrderRefundRevertFailureReason failureReason,
        boolean alreadyProcessed
) {
    public static OrderRefundRevertResult processed(Long refundId, Long orderId, Long buyerId, long amount,
                                                    WalletOrderRefundRevertStatus status,
                                                    WalletOrderRefundRevertFailureReason failureReason) {
        return new OrderRefundRevertResult(refundId, orderId, buyerId, amount, status, failureReason, false);
    }

    public static OrderRefundRevertResult alreadyProcessed(WalletOrderRefundRevert revert) {
        return new OrderRefundRevertResult(revert.getRefundId(), revert.getOrderId(), revert.getBuyerId(),
                revert.getAmount(), revert.getStatus(), revert.getFailureReason(), true);
    }

    public boolean reverted() {
        return status == WalletOrderRefundRevertStatus.REVERTED;
    }
}
