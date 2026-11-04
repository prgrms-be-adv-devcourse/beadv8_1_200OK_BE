package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 상세 API를 두지 않으므로 목록 한 줄에 필요한 값을 모두 담는다. 배송비·거절 사유·처리 시각은 그 칸을 만드는 이슈에서 더한다
@Schema(description = "환불 요청 한 건")
public record RefundResponse(

        @Schema(description = "환불 요청 ID", example = "7")
        Long refundId,

        @Schema(description = "주문 ID", example = "10")
        Long orderId,

        @Schema(description = "주문 상품 ID", example = "101")
        Long orderItemId,

        @Schema(description = "상품명", example = "양말")
        String productName,

        @Schema(description = "옵션명", example = "흰색")
        String optionName,

        @Schema(description = "취소 개수", example = "1")
        int quantity,

        @Schema(description = "단가 (원)", example = "10000")
        long unitPrice,

        @Schema(description = "상품 금액 = 단가 x 개수 (원). 배송비는 판매자 승인 때 정해진다", example = "10000")
        long itemAmount,

        @Schema(description = "구매자가 적은 취소 사유 (적었을 때만)", example = "옵션을 잘못 골랐습니다.")
        String cancelReason,

        @Schema(description = "상태. 지금은 REQUESTED(판매자 승인 대기)뿐이다", example = "REQUESTED")
        String status,

        @Schema(description = "요청 시각")
        LocalDateTime requestedAt
) {

    public static RefundResponse from(RefundResult result) {
        return new RefundResponse(
                result.refundId(),
                result.orderId(),
                result.orderItemId(),
                result.productName(),
                result.optionName(),
                result.quantity(),
                result.unitPrice(),
                result.itemAmount(),
                result.cancelReason(),
                result.status(),
                result.requestedAt());
    }
}
