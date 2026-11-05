package com.ok.payout.adapter.in.event.dto;

import java.time.LocalDateTime;
import java.util.Objects;

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

    // 차감 기록 저장은 값 검사를 하지 않는다. Spring 이벤트 클래스(WalletPaymentCompletedEvent) 생성자가 필수값을 검사해 왔기 때문
    // Kafka로 받을 때도 같은 보장을 하도록 생성자에서 막는다. JSON을 읽다 실패하면 재시도 없이 오류 처리로 넘어간다
    public WalletPaymentCompletedMessage {
        Objects.requireNonNull(orderId, "orderId는 필수입니다.");
        Objects.requireNonNull(buyerId, "buyerId는 필수입니다.");
        Objects.requireNonNull(amount, "amount는 필수입니다.");
        Objects.requireNonNull(paidAt, "paidAt은 필수입니다.");
    }

    public WalletPaymentCompletedCommand toCommand() {
        return new WalletPaymentCompletedCommand(orderId, buyerId, amount, paidAt);
    }
}
