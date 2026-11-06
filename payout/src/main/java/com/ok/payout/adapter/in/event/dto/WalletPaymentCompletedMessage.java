package com.ok.payout.adapter.in.event.dto;

import java.time.LocalDateTime;

import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;

/**
 * 지갑이 Kafka로 보내는 결제 완료(예치금 차감) 메시지를 정산이 읽는 모양. 필드 이름은 지갑이 제안대로 동의했다(#339).
 * 지갑 클래스를 import하지 않고 같은 JSON 모양의 정산 DTO로 읽는다 (컨벤션 2.1).
 */
public record WalletPaymentCompletedMessage(
        // 결제한 주문 ID. 정산 차감 기록의 중복 방지 기준
        Long orderId,
        // 구매자 회원 ID
        Long buyerId,
        // 차감한 금액(원)
        Long amount,
        // 지갑이 차감한 시각 (Asia/Seoul)
        LocalDateTime paidAt
) {

    // 값 검사는 입구(PayoutInboundEventValidator)가 한다.
    // Kafka로 받으면 null과 음수가 모두 입구까지 오고, Spring 이벤트로 받으면 공통 이벤트 생성자가 null을 먼저 거절해 음수만 온다
    public WalletPaymentCompletedCommand toCommand() {
        return new WalletPaymentCompletedCommand(orderId, buyerId, amount, paidAt);
    }
}
