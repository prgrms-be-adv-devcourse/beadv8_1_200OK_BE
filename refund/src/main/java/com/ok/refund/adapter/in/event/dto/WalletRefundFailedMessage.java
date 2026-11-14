package com.ok.refund.adapter.in.event.dto;

import com.ok.refund.application.port.in.WalletRefundFailedCommand;

/**
 * 지갑이 Kafka로 보내는 예치금 반환 실패 메시지를 환불이 읽는 모양. 필드 이름·타입은 지갑이 제안한 약속이다(#544).
 * 지갑 클래스를 import하지 않고 같은 JSON 모양의 환불 DTO로 읽는다 (컨벤션 2.1).
 * amount는 필드가 빠진 메시지를 0으로 읽지 않으려고 Long으로 받고, 값 검사는 리스너가 한다.
 */
public record WalletRefundFailedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        Long amount,
        String reason
) {

    // amount가 null이면 리스너가 먼저 거절하므로 여기서는 값이 있다고 본다
    public WalletRefundFailedCommand toCommand() {
        return new WalletRefundFailedCommand(refundId, orderId, buyerId, amount, reason);
    }
}
