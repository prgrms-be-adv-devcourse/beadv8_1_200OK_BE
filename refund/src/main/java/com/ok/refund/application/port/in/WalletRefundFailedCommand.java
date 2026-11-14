package com.ok.refund.application.port.in;

/**
 * 지갑이 예치금 반환에 실패했다고 알려 준 결과.
 *
 * @param amount 지갑이 반환하려던 금액 (원)
 * @param reason 지갑이 알려 준 실패 이유. 환불의 실패 이유(failureCode)에 그대로 남긴다
 */
public record WalletRefundFailedCommand(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount,
        String reason
) {
}
