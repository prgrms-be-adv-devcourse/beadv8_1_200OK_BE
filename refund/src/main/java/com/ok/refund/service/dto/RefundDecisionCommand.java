package com.ok.refund.service.dto;

/**
 * 판매자의 승인·거절.
 *
 * @param sellerId     로그인 토큰에서 꺼낸 판매자 ID
 * @param rejectReason 거절일 때만 필수
 */
public record RefundDecisionCommand(Long refundId, Long sellerId, RefundDecision decision, String rejectReason) {
}
