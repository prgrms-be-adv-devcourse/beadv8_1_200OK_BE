package com.ok.payout.event.dto;

import java.time.LocalDateTime;

import com.ok.payout.service.dto.SellerPayoutCreatedCommand;

/**
 * 정산이 지갑으로 보내는 정산 확정 메시지. Kafka에 JSON으로 실려 나간다.
 * 필드 이름·타입·단위가 지갑과의 약속(#339 "정산이 보내는 JSON 약속")이라, 바꾸면 지갑 담당과 먼저 맞춘다.
 * 지갑은 이 클래스를 import하지 않고 같은 모양의 자기 DTO로 읽는다 (컨벤션 2.1).
 */
public record SellerPayoutCreatedMessage(
        // 정산 ID. 지갑의 중복 입금 방지 기준
        Long payoutId,
        // 판매자 회원 ID. null 아님 (시스템 몫은 보내지 않음)
        Long payeeId,
        // 원 단위, 항상 1 이상 (0원 이하는 PayoutService에서 걸러 보내지 않음, #106)
        Long amount,
        // 정산 확정 시각 (Asia/Seoul)
        LocalDateTime createdAt
) {

    public static SellerPayoutCreatedMessage from(SellerPayoutCreatedCommand command) {
        return new SellerPayoutCreatedMessage(command.payoutId(), command.payeeId(), command.amount(), command.createdAt());
    }
}
