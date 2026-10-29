package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "환불 요청 승인·거절")
public record RefundDecisionRequest(

        // enum으로 받으면 잘못된 값이 본문 해석 오류가 되어 필드별 검증 오류를 알려줄 수 없으므로 문자열로 검사한다(배송지 변경 결정과 같음)
        @Schema(description = "처리 결정 (APPROVE, REJECT)", example = "APPROVE")
        @NotBlank(message = "처리 결정은 필수입니다.")
        @Pattern(regexp = "APPROVE|REJECT", message = "처리 결정은 APPROVE, REJECT 중 하나여야 합니다.")
        String decision,

        // 거절일 때만 필수라 형식만 여기서 보고, 필수 여부는 서비스(도메인)가 확인한다
        @Schema(description = "거절 사유 (REJECT일 때 필수, 255자 이하)", example = "이미 포장을 마쳤습니다.")
        @Size(max = 255, message = "거절 사유는 255자 이하여야 합니다.")
        String rejectReason
) {

    public RefundDecisionCommand toCommand(Long refundId, Long sellerId) {
        return new RefundDecisionCommand(refundId, sellerId, RefundDecision.valueOf(decision), rejectReason);
    }
}
