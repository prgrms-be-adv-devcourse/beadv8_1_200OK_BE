package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationRejectCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "판매자 신청 거절 요청")
public record SellerApplicationRejectRequest(

        @Schema(description = "거절 사유 (500자 이하, 앞뒤 공백 제거). 신청한 회원의 신청 내역에 그대로 보인다",
                example = "통신판매업 신고번호를 확인할 수 없습니다. 신고증의 번호를 다시 확인해 주세요.")
        @NotBlank(message = "거절 사유는 필수입니다.")
        @Size(max = 500, message = "거절 사유는 500자 이하여야 합니다.")
        String reason
) {

    /**
     * 요청을 역직렬화하는 시점에 앞뒤 공백을 지운다. 검증은 공백을 지운 값을 기준으로 수행된다.
     */
    public SellerApplicationRejectRequest {
        reason = reason == null ? null : reason.strip();
    }

    public SellerApplicationRejectCommand toCommand() {
        return new SellerApplicationRejectCommand(reason);
    }

}
