package com.ok.refund.client.dto;

/**
 * 지갑 모듈 {@code POST /internal/v1/wallets/refunds} 요청(요청서 10.1 기준 가정).
 */
public record WalletRefundRequest(Long refundId, Long orderId, Long buyerId, Long sellerId, long amount) {
}
