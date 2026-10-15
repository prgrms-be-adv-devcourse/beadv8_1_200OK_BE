package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutItemResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "정산 항목 1건 (정산후보 정보 포함)")
public record PayoutItemResponse(

        @Schema(description = "정산 항목 ID", example = "101")
        Long payoutItemId,

        @Schema(description = "이 항목이 된 정산후보 ID", example = "55")
        Long candidateId,

        @Schema(description = "항목 종류", example = "SALE_AMOUNT", allowableValues = {"SALE_AMOUNT", "SALE_SHIPPING_FEE", "SALE_FEE"})
        String eventType,

        @Schema(description = "주문 항목 ID", example = "900")
        Long orderItemId,

        @Schema(description = "판매자 회원 ID. 수수료 항목이면 어느 판매자 판매에서 나온 수수료인지", example = "7")
        Long sellerId,

        @Schema(description = "항목 금액 (원). 조정 건이면 음수일 수 있음", example = "9700")
        Long amount,

        @Schema(description = "정산후보 생성 시각. 평소 건은 구매확정 직후, 조정 건은 조정한 시각", example = "2026-10-14T15:20:00")
        LocalDateTime candidateCreatedAt,

        @Schema(description = "관리자 조정 정보. 평소 건이면 null", nullable = true)
        PayoutAdjustmentResponse adjustment
) {

    public static PayoutItemResponse from(PayoutItemResult result) {
        PayoutAdjustmentResponse adjustment = result.adjustment() == null
                ? null
                : PayoutAdjustmentResponse.from(result.adjustment());
        return new PayoutItemResponse(result.payoutItemId(), result.candidateId(), result.eventType(),
                result.orderItemId(), result.sellerId(), result.amount(), result.candidateCreatedAt(), adjustment);
    }
}
