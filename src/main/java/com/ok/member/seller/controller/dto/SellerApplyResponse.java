package com.ok.member.seller.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 등록 신청 응답")
public record SellerApplyResponse(

        @Schema(description = "생성된 판매자 신청 id", example = "1")
        Long applicationId
) {
}
