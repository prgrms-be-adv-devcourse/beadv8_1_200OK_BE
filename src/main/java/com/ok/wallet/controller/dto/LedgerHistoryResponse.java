package com.ok.wallet.controller.dto;

import com.ok.wallet.service.dto.LedgerHistoryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "이용 내역 한 줄")
public record LedgerHistoryResponse(
        @Schema(description = "일시", example = "2026-10-19T15:20:00")
        LocalDateTime occurredAt,

        @Schema(description = "유형 (CHARGE: 충전, PAYMENT: 결제)", example = "PAYMENT")
        String type,

        @Schema(description = "주문 ID. 결제 내역일 때만 있고 충전이면 null", example = "1234")
        Long orderId,

        @Schema(description = "금액 (원, 항상 양수). 충전은 +, 결제는 -로 표시", example = "30000")
        long amount,

        @Schema(description = "거래 후 잔액 (원)", example = "70000")
        long balanceAfter
) {
    public static LedgerHistoryResponse from(LedgerHistoryResult result) {
        return new LedgerHistoryResponse(
                result.occurredAt(), result.type(), result.orderId(), result.amount(), result.balanceAfter());
    }
}
