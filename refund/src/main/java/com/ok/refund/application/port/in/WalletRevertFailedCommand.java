package com.ok.refund.application.port.in;

/**
 * 지갑이 돈을 다시 가져오지 못했다고 알려 준 결과(#546).
 *
 * @param amount 지갑이 다시 가져오려던 금액 (원)
 * @param reason 지갑이 알려 준 실패 이유(예: {@code INSUFFICIENT_BALANCE}). 회수 줄의 이유(last_reason)에 그대로 남긴다
 */
public record WalletRevertFailedCommand(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount,
        String reason
) {
}
