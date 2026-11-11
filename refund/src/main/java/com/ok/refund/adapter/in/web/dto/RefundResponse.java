package com.ok.refund.adapter.in.web.dto;

import com.ok.refund.application.port.in.RefundResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 상세 API를 두지 않으므로 목록 한 줄에 필요한 값을 모두 담는다. 완료 시각은 그 칸을 만드는 이슈(#414)에서 더한다.
// 돌려줄 금액 합계를 서버가 계산해 주는 이유: 화면마다 더하고 빼면 부호 실수로 금액이 어긋날 수 있다
// 거절·철회도 승인 때 정한 금액 칸을 그대로 보여 준다. 끝난 요청인지는 상태로 구분한다
@Schema(description = "환불 요청 한 건")
public record RefundResponse(

        @Schema(description = "환불 요청 ID", example = "7")
        Long refundId,

        @Schema(description = "주문 ID", example = "10")
        Long orderId,

        @Schema(description = "주문 상품 ID", example = "101")
        Long orderItemId,

        @Schema(description = "상품명", example = "감자")
        String productName,

        @Schema(description = "옵션명", example = "1kg")
        String optionName,

        @Schema(description = "취소 개수", example = "1")
        int quantity,

        @Schema(description = "단가 (원)", example = "10000")
        long unitPrice,

        @Schema(description = "상품 금액 = 단가 x 개수 (원). 배송비는 판매자 승인 때 정해진다", example = "10000")
        long itemAmount,

        @Schema(description = "돌려줄 배송비 (원). 판매자 묶음(한 주문 안 같은 판매자 상품)이 이 환불로 전부 취소될 때만 "
                + "낸 배송비와 앞선 환불에서 뺀 배송비를 돌려준다. 승인 전에는 0", example = "3000")
        long shippingFeeAmount,

        @Schema(description = "돌려줄 도서산간 운임 (원). 판매자 묶음이 이 환불로 전부 취소될 때만. 승인 전에는 0", example = "0")
        long remoteAreaFeeAmount,

        @Schema(description = "환불 금액에서 뺀 배송비 (원). 일부 취소로 남은 묶음 금액이 무료배송 기준 아래로 처음 내려가면 "
                + "판매자의 기본 배송비를 뺀다(남은 상품을 보낼 택배비). 아니면 0. 승인 전에는 0", example = "0")
        long deductedShippingFeeAmount,

        @Schema(description = "돌려줄 금액 (원) = 상품 금액 + 돌려줄 배송비 + 도서산간 운임 - 뺀 배송비. 승인 전에는 상품 금액과 같다",
                example = "13000")
        long refundAmount,

        @Schema(description = "구매자가 적은 취소 사유 (적었을 때만)", example = "옵션을 잘못 골랐습니다.")
        String cancelReason,

        @Schema(description = "상태. REQUESTED(판매자 승인 대기), PROCESSING(승인되어 처리 중), REJECTED(판매자 거절), WITHDRAWN(구매자 철회), COMPLETED(지갑 반환 요청·주문 반영·재고 복구까지 마침)",
                example = "REQUESTED")
        String status,

        @Schema(description = "거절 사유 (거절일 때만). 판매자가 적은 사유이거나, 승인 때 배송비가 모자라 자동 거절됐으면 그 이유",
                example = "이미 포장을 마쳤습니다.")
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
                result.shippingFeeAmount(),
                result.remoteAreaFeeAmount(),
                result.deductedShippingFeeAmount(),
                result.refundAmount(),
                result.cancelReason(),
                result.status(),
                result.rejectReason(),
                result.requestedAt(),
                result.decidedAt());
    }
}
