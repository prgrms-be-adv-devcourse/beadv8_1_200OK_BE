package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.ReviewCandidateResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "리뷰를 쓸 수 있는 구매 건 (주문 상품 단위)")
public record ReviewCandidateResponse(

        @Schema(description = "주문 ID", example = "1001")
        Long orderId,

        @Schema(description = "주문 상품 ID. 리뷰 저장 직전 검증에 쓴다", example = "5001")
        Long orderItemId,

        @Schema(description = "상품 ID", example = "10")
        Long productId,

        @Schema(description = "구매한 옵션 ID", example = "12")
        Long productOptionId,

        @Schema(description = "주문 시각", example = "2026-09-20T10:00:00")
        LocalDateTime orderedAt
) {
    public static ReviewCandidateResponse from(ReviewCandidateResult result) {
        return new ReviewCandidateResponse(
                result.orderId(),
                result.orderItemId(),
                result.productId(),
                result.productOptionId(),
                result.orderedAt()
        );
    }
}