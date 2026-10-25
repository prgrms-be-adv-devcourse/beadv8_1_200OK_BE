package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutAdjustmentResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 정산 항목의 조정 정보")
public record PayoutAdjustmentResponse(

        @Schema(description = "관리자 조정 번호", example = "4")
        Long adjustmentId,

        @Schema(description = "조정한 관리자 회원 ID", example = "1")
        Long adminId,

        @Schema(description = "조정 사유. 관리자가 입력한 원문이라 화면에 출력할 때 HTML 이스케이프 필요", example = "금액 오입력 정정")
        String reason
) {

    public static PayoutAdjustmentResponse from(PayoutAdjustmentResult result) {
        return new PayoutAdjustmentResponse(result.adjustmentId(), result.adminId(), result.reason());
    }
}
