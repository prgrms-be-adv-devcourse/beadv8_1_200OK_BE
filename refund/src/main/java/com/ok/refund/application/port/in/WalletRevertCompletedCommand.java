package com.ok.refund.application.port.in;

/**
 * 지갑이 돈을 다시 가져왔다고(회수) 알려 준 결과(#546).
 *
 * @param buyerId 지갑이 실제로 돈을 돌려줬던 기록의 구매자
 * @param amount  지갑이 실제로 돌려줬던 기록의 금액 (원)
 */
public record WalletRevertCompletedCommand(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount
) {
}
