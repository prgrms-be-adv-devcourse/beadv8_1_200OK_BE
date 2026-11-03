package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 상세 API를 두지 않으므로 목록 한 줄에 필요한 값을 모두 담는다(설계 4.3)
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

        @Schema(description = "상품 금액 = 단가 x 개수 (원)", example = "10000")
        long itemAmount,

        @Schema(description = "돌려줄 배송비 (원). 승인 때 판매자 묶음이 전부 취소되면 채워지고, 그 전과 거절·철회·승인 불가면 0", example = "0")
        long shippingFeeAmount,

        @Schema(description = "돌려줄 도서산간 추가 운임 (원). 승인 때 판매자 묶음이 전부 취소되면 채워지고, 그 전과 거절·철회·승인 불가면 0", example = "0")
        long remoteAreaFeeAmount,

        @Schema(description = "환불에서 뺀 기본 배송비 (원). 승인 때 일부 취소로 무료배송 기준이 깨지면 채워지고, 그 전과 거절·철회·승인 불가면 0", example = "0")
        long deductedShippingFeeAmount,

        @Schema(description = "돌려줄 금액 = 상품 금액 + 배송비 + 추가 운임 − 뺀 배송비 (원). 요청됨일 때는 승인 뒤 바뀔 수 있고, 거절·철회·승인 불가면 0", example = "10000")
        long refundAmount,

        @Schema(description = "상태 (REQUESTED, REJECTED, WITHDRAWN, PROCESSING, NOT_APPROVABLE, COMPLETED). "
                + "NOT_APPROVABLE은 이미 출고되어 취소할 수 없다는 뜻이며 반품을 안내한다", example = "REQUESTED")
        String status,

        @Schema(description = "판매자 거절 사유 (거절일 때만)", example = "이미 포장을 마쳤습니다.")
        String rejectReason,

        @Schema(description = "처리 중에 멈춘 단계 (멈췄을 때만). SHIPMENT_RESERVE, WALLET, ORDER, PRODUCT, SHIPMENT_CONFIRM", example = "WALLET")
        String failedStep,

        @Schema(description = "요청 시각")
        LocalDateTime requestedAt,

        @Schema(description = "승인·거절·철회 시각")
        LocalDateTime decidedAt,

        @Schema(description = "완료·승인 불가 시각")
        LocalDateTime completedAt
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
                result.shippingFeeAmount(),
                result.remoteAreaFeeAmount(),
                result.deductedShippingFeeAmount(),
                result.refundAmount(),
                result.status(),
                result.rejectReason(),
                result.failedStep(),
                result.requestedAt(),
                result.decidedAt(),
                result.completedAt());
    }
}
