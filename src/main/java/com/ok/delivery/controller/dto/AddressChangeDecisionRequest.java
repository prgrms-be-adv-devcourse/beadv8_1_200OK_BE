package com.ok.delivery.controller.dto;

import com.ok.delivery.service.dto.AddressChangeDecision;
import com.ok.delivery.service.dto.AddressChangeDecisionCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "배송지 변경 요청 승인·거절")
public record AddressChangeDecisionRequest(

        // enum으로 받으면 잘못된 값이 본문 해석 오류가 되어 필드별 검증 오류를 돌려줄 수 없으므로 문자열로 검사한다
        @Schema(description = "처리 결정 (APPROVE, REJECT)", example = "APPROVE")
        @NotBlank(message = "처리 결정은 필수입니다.")
        @Pattern(regexp = "APPROVE|REJECT", message = "처리 결정은 APPROVE, REJECT 중 하나여야 합니다.")
        String decision
) {

    public AddressChangeDecisionCommand toCommand(Long shipmentId, Long requestId, Long sellerId) {
        return new AddressChangeDecisionCommand(shipmentId, requestId, sellerId, AddressChangeDecision.valueOf(decision));
    }
}
