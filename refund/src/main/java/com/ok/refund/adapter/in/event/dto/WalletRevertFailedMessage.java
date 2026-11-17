package com.ok.refund.adapter.in.event.dto;

import com.ok.refund.application.port.in.WalletRevertFailedCommand;

/**
 * 지갑이 Kafka로 보내는 회수 실패 메시지를 환불이 읽는 모양. 필드 이름·타입은 PR #537에서 지갑과 합의한 약속이다(#546).
 * 회수 완료 메시지 필드에 실패 이유({@code reason}, 예: {@code INSUFFICIENT_BALANCE})가 더해진다.
 * amount는 필드가 빠진 메시지를 0으로 읽지 않으려고 Long으로 받고, 값 검사는 리스너가 한다.
 */
public record WalletRevertFailedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        Long amount,
        String reason
) {

    // amount가 null이면 리스너가 먼저 거절하므로 여기서는 값이 있다고 본다
    public WalletRevertFailedCommand toCommand() {
        return new WalletRevertFailedCommand(refundId, orderId, buyerId, amount, reason);
    }
}
