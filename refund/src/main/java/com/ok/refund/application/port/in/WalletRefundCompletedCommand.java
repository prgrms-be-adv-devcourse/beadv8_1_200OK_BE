package com.ok.refund.application.port.in;

/**
 * 지갑이 예치금 반환을 마쳤다고 알려 준 결과.
 *
 * @param orderId 지갑이 반환한 주문. 환불의 주문과 같은지 서비스가 확인한다
 * @param buyerId 지갑이 반환한 구매자
 * @param amount  지갑이 실제로 돌려준 금액 (원). 환불이 지갑에 요청한 금액과 같은지 서비스가 확인한다
 */
public record WalletRefundCompletedCommand(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount
) {
}
