package com.ok.refund.adapter.in.event.dto;

import com.ok.refund.application.port.in.WalletRefundCompletedCommand;

/**
 * 지갑이 Kafka로 보내는 예치금 반환 완료 메시지를 환불이 읽는 모양. 필드 이름·타입은 지갑이 제안한 약속이다(#544).
 * 지갑 클래스를 import하지 않고 같은 JSON 모양의 환불 DTO로 읽는다 (컨벤션 2.1).
 * 값이 비었는지는 DTO가 아니라 리스너가 검사한다. 빠진 필드를 0으로 읽어 버리지 않도록 amount는 Long으로 받는다.
 */
public record WalletRefundCompletedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        Long amount
) {

    // amount가 null이면 리스너가 먼저 거절하므로 여기서는 값이 있다고 본다
    public WalletRefundCompletedCommand toCommand() {
        return new WalletRefundCompletedCommand(refundId, orderId, buyerId, amount);
    }
}
