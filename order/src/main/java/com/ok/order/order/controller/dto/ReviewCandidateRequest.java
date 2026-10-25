package com.ok.order.order.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "리뷰 작성 가능 구매 건 조회 조건")
public record ReviewCandidateRequest(

        @Schema(description = "구매자 회원 ID", example = "1")
        @NotNull(message = "회원 ID는 필수입니다.")
        Long memberId,

        @Schema(description = "상품 ID", example = "10")
        @NotNull(message = "상품 ID는 필수입니다.")
        Long productId
) {
}