package com.ok.wallet.adapter.in.web.dto;

import com.ok.wallet.application.dto.LedgerHistoryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "이용 내역 한 줄")
public record LedgerHistoryResponse(
        @Schema(description = "일시", example = "2026-10-19T15:20:00")
        LocalDateTime occurredAt,

        @Schema(description = "유형 (CHARGE: 충전, PAYMENT: 결제, REFUND: 환불 반환, "
                + "REFUND_REVERT: 환불 되돌리기로 반환한 금액을 다시 가져감)", example = "PAYMENT")
        String type,

        @Schema(description = "주문 ID. 결제·환불 반환·환불 되돌리기 내역에 있고 충전이면 null. "
                + "환불 기록을 찾지 못한 드문 경우에도 null", example = "1234")
        Long orderId,

        @Schema(description = "주문 요약 (첫 상품 이름 + 나머지 상품 개수, 주문 시점 이름, 최대 120자). "
                + "결제·환불 반환·환불 되돌리기 내역에 있고 충전이면 null. 요약을 저장하기 전 결제는 null",
                example = "사과 외 3건")
        String orderSummary,

        @Schema(description = "금액 (원, 항상 양수). 충전·환불 반환은 +, 결제·환불 되돌리기는 -로 표시", example = "30000")
        long amount,

        @Schema(description = "거래 후 잔액 (원)", example = "70000")
        long balanceAfter
) {
    public static LedgerHistoryResponse from(LedgerHistoryResult result) {
        return new LedgerHistoryResponse(
                result.occurredAt(), result.type(), result.orderId(), result.orderSummary(),
                result.amount(), result.balanceAfter());
    }
}
