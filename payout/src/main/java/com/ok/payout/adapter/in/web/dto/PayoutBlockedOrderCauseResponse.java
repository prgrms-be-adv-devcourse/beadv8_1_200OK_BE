package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutBlockedOrderCauseResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "막힌 주문의 원인 1건. 같은 주문의 다른 상품 때문에 함께 보류된 기록은 빠져 있고, 같은 기록은 1번만 나옴")
public record PayoutBlockedOrderCauseResponse(

        @Schema(description = "주문 항목 ID. 배송비 후보면 null", example = "1001", nullable = true)
        Long orderItemId,

        @Schema(description = "실패한 정산후보의 종류", example = "SALE_FEE",
                allowableValues = {"SALE_AMOUNT", "SALE_SHIPPING_FEE", "SALE_FEE"})
        String eventType,

        @Schema(description = "실패 사유 (예: AMOUNT_MISMATCH, FEE_MISMATCH, VERIFY_MISMATCH, DEDUCTION_MISSING)", example = "FEE_MISMATCH")
        String reason,

        @Schema(description = "실패 사유의 원인 범주", example = "SYSTEM",
                allowableValues = {"SOURCE_DATA", "SYSTEM", "RECONCILIATION"})
        String category,

        @Schema(description = "실패 상세 (검사 종류, 기대값·실제값 등)", example = "수수료 불일치: 기대 300, 실제 500")
        String detail,

        @Schema(description = "판매자 회원 ID", example = "7")
        Long sellerId,

        @Schema(description = "같은 원인 기록 중 가장 늦은 실패 시각", example = "2026-11-14T10:00:00")
        LocalDateTime failedAt
) {

    public static PayoutBlockedOrderCauseResponse from(PayoutBlockedOrderCauseResult result) {
        return new PayoutBlockedOrderCauseResponse(result.orderItemId(), result.eventType(), result.reason(),
                result.category(), result.detail(), result.sellerId(), result.failedAt());
    }
}
