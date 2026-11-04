package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 상세 API를 두지 않으므로 목록 한 줄에 필요한 값을 모두 담는다. 배송비·완료 시각은 그 칸을 만드는 이슈(#410, #414)에서 더한다
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

        @Schema(description = "상태. REQUESTED(판매자 승인 대기), PROCESSING(승인되어 처리 중), REJECTED(판매자 거절), WITHDRAWN(구매자 철회)",
                example = "REQUESTED")
        String status,

        @Schema(description = "판매자가 적은 거절 사유 (거절일 때만)", example = "이미 포장을 마쳤습니다.")
        String rejectReason,

        @Schema(description = "요청 시각")
        LocalDateTime requestedAt,

        @Schema(description = "판매자가 승인·거절하거나 구매자가 철회한 시각 (요청됨이면 없음)")
        LocalDateTime decidedAt
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
                result.rejectReason(),
                result.requestedAt(),
                result.decidedAt());
    }
}
