package com.ok.refund.application.port.in;

/**
 * 판매자의 승인·거절 입력.
 *
 * @param sellerId     토큰에서 꺼낸 판매자 ID. 본인 상품의 요청인지 확인한다
 * @param rejectReason 거절 사유. 승인이면 쓰지 않는다
 */
public record RefundDecisionCommand(
        Long refundId,
        Long sellerId,
        RefundDecision decision,
        String rejectReason
) {
}
