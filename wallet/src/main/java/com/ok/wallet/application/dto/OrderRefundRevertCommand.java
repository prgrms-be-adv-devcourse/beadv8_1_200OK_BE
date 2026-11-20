package com.ok.wallet.application.dto;

/**
 * 환불이 요청한 되돌리기(돌려준 예치금 다시 가져오기).
 *
 * @param amount 환불이 아는 반환 금액(원). 빠진 값을 0으로 읽지 않으려고 Long으로 받고, 값 검사는 서비스가 한다
 */
public record OrderRefundRevertCommand(Long refundId, Long orderId, Long buyerId, Long amount) {
}
