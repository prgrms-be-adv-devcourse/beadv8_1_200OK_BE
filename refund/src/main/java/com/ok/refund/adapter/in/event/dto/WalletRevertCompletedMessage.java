package com.ok.refund.adapter.in.event.dto;

import com.ok.refund.application.port.in.WalletRevertCompletedCommand;

/**
 * 지갑이 Kafka로 보내는 회수 완료(다시 가져옴) 메시지를 환불이 읽는 모양. 필드 이름·타입은 PR #537에서 지갑과 합의한 약속이다(#546).
 * buyerId·amount는 지갑이 실제로 돌려줬던 기록 값이다. 지갑 클래스를 import하지 않고 같은 JSON 모양의 환불 DTO로 읽는다.
 * amount는 필드가 빠진 메시지를 0으로 읽지 않으려고 Long으로 받고, 값 검사는 리스너가 한다.
 */
public record WalletRevertCompletedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        Long amount
) {

    // amount가 null이면 리스너가 먼저 거절하므로 여기서는 값이 있다고 본다
    public WalletRevertCompletedCommand toCommand() {
        return new WalletRevertCompletedCommand(refundId, orderId, buyerId, amount);
    }
}
