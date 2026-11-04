package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "환불 요청 승인·거절")
public record RefundDecisionRequest(

        // enum으로 받으면 잘못된 값이 본문 해석 오류가 되어 어느 칸이 틀렸는지 알려줄 수 없으므로 문자열로 검사한다
        @Schema(description = "처리 결정 (APPROVE: 승인, REJECT: 거절)", example = "APPROVE")
        @NotBlank(message = "처리 결정은 필수입니다.")
        @Pattern(regexp = "APPROVE|REJECT", message = "처리 결정은 APPROVE, REJECT 중 하나여야 합니다.")
        String decision,

        // 거절일 때만 필수라 여기서는 길이만 보고, 필수 여부는 도메인(Refund.reject)이 확인한다.
        // 승인이면 값은 쓰지 않고 버리지만, 길이 검사는 결정 값과 상관없이 한다
        @Schema(description = "거절 사유 (REJECT일 때 필수, 255자 이하). 구매자 목록에 보인다. "
                + "APPROVE일 때는 보내도 저장하지 않지만 255자 제한은 똑같이 검사한다", example = "이미 포장을 마쳤습니다.")
        @Size(max = 255, message = "거절 사유는 255자 이하여야 합니다.")
        String rejectReason
) {

    // 공백만 적은 사유는 안 적은 것과 같게 본다. 그래야 공백 사유로 거절하는 것을 도메인이 "사유 없음"으로 막는다
    public RefundDecisionRequest {
        rejectReason = rejectReason == null || rejectReason.isBlank() ? null : rejectReason.strip();
    }

    // 판매자 ID는 본문이 아니라 로그인 토큰에서 받는다. 본문으로 받으면 남의 상품 요청을 결정할 수 있다
    public RefundDecisionCommand toCommand(Long refundId, Long sellerId) {
        return new RefundDecisionCommand(refundId, sellerId, RefundDecision.valueOf(decision), rejectReason);
    }
}
