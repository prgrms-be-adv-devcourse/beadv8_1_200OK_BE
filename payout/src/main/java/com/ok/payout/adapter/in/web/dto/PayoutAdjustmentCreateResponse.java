package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutAdjustmentCreateResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "정산 수동 조정 결과: 방금 저장한 조정 정산후보 (#291)")
public record PayoutAdjustmentCreateResponse(

        @Schema(description = "생성된 조정 정산후보 ID", example = "1234")
        Long candidateId,

        @Schema(description = "서버가 붙인 조정 번호. 같은 주문 항목·종류에서 1, 2, 3… 순서", example = "1")
        Long adjustmentId,

        @Schema(description = "주문 ID (원래 정산후보에서 가져온 값)", example = "100")
        Long orderId,

        @Schema(description = "주문 항목 ID", example = "900")
        Long orderItemId,

        @Schema(description = "판매자 회원 ID (원래 정산후보에서 가져온 값)", example = "7")
        Long sellerId,

        @Schema(description = "정산 항목 종류", example = "SALE_AMOUNT")
        String eventType,

        @Schema(description = "조정 금액 (원). 음수면 차감", example = "-2000")
        Long amount,

        @Schema(description = "조정 사유", example = "금액 오입력 정정")
        String reason,

        @Schema(description = "조정한 관리자 회원 ID", example = "1")
        Long adminId,

        @Schema(description = "조정 생성 시각", example = "2026-11-16T10:00:00")
        LocalDateTime createdAt
) {

    public static PayoutAdjustmentCreateResponse from(PayoutAdjustmentCreateResult result) {
        return new PayoutAdjustmentCreateResponse(result.candidateId(), result.adjustmentId(), result.orderId(),
                result.orderItemId(), result.sellerId(), result.eventType(), result.amount(), result.reason(),
                result.adminId(), result.createdAt());
    }
}
