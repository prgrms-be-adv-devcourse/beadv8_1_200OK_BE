package com.ok.refund.adapter.out.event;

import com.ok.refund.application.port.out.WalletRevertRequest;

/**
 * {@link RefundTopics#REFUND_WALLET_REVERT_REQUESTED} 토픽에 보내는 메시지. 이 필드 이름·타입이 지갑과의 약속이다(PR #537, #546).
 * 지갑은 이 클래스가 아니라 JSON 모양으로 읽는다(타입 헤더를 싣지 않음). 필드를 바꾸면 지갑 쪽도 같이 바꿔야 한다.
 *
 * @param refundId 지갑이 중복을 가르는 값. 같은 refundId가 다시 오면 다시 가져오지 않고 첫 결과를 다시 보낸다(지갑 쪽 약속)
 * @param amount   다시 가져올 금액 (원, 1 이상)
 */
public record RefundWalletRevertRequestedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount
) {

    static RefundWalletRevertRequestedMessage from(WalletRevertRequest request) {
        return new RefundWalletRevertRequestedMessage(request.refundId(), request.orderId(), request.buyerId(), request.amount());
    }
}
