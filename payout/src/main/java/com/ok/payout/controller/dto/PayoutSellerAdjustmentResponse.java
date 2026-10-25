package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutAdjustmentResult;
import io.swagger.v3.oas.annotations.media.Schema;

// 판매자가 보는 조정 정보. 관리자용(PayoutAdjustmentResponse)에서 조정 번호·관리자 ID를 뺀 응답
// 조정 번호는 판매자가 쓸 데 없는 내부 번호, 관리자 ID는 내부 직원 정보라 옮기지 않음
@Schema(description = "판매자 정산 항목의 관리자 조정 정보")
public record PayoutSellerAdjustmentResponse(

        @Schema(description = "조정 사유. 관리자가 입력한 원문이라 화면에 출력할 때 HTML 이스케이프 필요", example = "금액 오입력 정정")
        String reason
) {

    public static PayoutSellerAdjustmentResponse from(PayoutAdjustmentResult result) {
        return new PayoutSellerAdjustmentResponse(result.reason());
    }
}
