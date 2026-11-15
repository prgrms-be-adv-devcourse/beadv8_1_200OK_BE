package com.ok.wallet.application.dto;

/**
 * 환불이 승인한 예치금 반환 요청.
 *
 * @param amount 돌려줄 금액(원). 빠진 값을 0으로 읽지 않으려고 Long으로 받고, 값 검사는 서비스가 한다
 */
public record OrderRefundCommand(Long refundId, Long orderId, Long buyerId, Long amount) {
}
