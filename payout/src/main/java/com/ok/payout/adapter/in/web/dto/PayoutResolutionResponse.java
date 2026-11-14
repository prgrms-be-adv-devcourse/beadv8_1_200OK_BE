package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutResolutionResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "해결 기록 1건 (실패 기록 1건 = 1줄). 해결 기록과 그 해결 기록이 푼 실패 기록·정산후보 정보")
public record PayoutResolutionResponse(

        @Schema(description = "해결 기록 ID", example = "31")
        Long resolutionId,

        @Schema(description = "해결 요청 번호 (UUID). 같은 해결 요청에서 생긴 줄은 모두 같음. 묶음 보기의 요청 번호와 같은 값",
                example = "3f2b8c1e-7d4a-4b9e-9c21-5a6d8e0f1b23")
        String resolutionRequestId,

        @Schema(description = "해결한 실패 기록 ID", example = "11")
        Long failureId,

        @Schema(description = "주문 ID", example = "100")
        Long orderId,

        @Schema(description = "주문 항목 ID. 배송비 실패는 null", example = "1001", nullable = true)
        Long orderItemId,

        @Schema(description = "판매자 회원 ID", example = "7")
        Long sellerId,

        @Schema(description = "실패한 정산후보의 종류", example = "SALE_AMOUNT",
                allowableValues = {"SALE_AMOUNT", "SALE_SHIPPING_FEE", "SALE_FEE"})
        String eventType,

        @Schema(description = "실패한 정산후보의 금액 (원)", example = "10000")
        Long amount,

        @Schema(description = "실패 사유 (예: AMOUNT_MISMATCH, FEE_MISMATCH, VERIFY_MISMATCH, ORDER_HELD)", example = "AMOUNT_MISMATCH")
        String reason,

        @Schema(description = "실패 사유의 원인 범주", example = "SOURCE_DATA",
                allowableValues = {"SOURCE_DATA", "SYSTEM", "RECONCILIATION"})
        String category,

        @Schema(description = "실패 상세 (검사 종류, 기대값·실제값 등)", example = "결제 금액 9,000원 / 정산 대상 10,000원")
        String detail,

        @Schema(description = "실패 시각", example = "2026-11-13T10:00:00")
        LocalDateTime failedAt,

        @Schema(description = "해결 방법", example = "REPROCESS")
        String method,

        @Schema(description = "해결한 관리자 회원 ID", example = "1")
        Long adminId,

        @Schema(description = "해결 시각", example = "2026-11-14T10:00:00")
        LocalDateTime resolvedAt,

        @Schema(description = "해결 메모. 관리자가 입력한 원문이라 화면에 출력할 때 HTML 이스케이프가 필요합니다",
                example = "차감액을 9,000원으로 바로잡음")
        String memo
) {

    public static PayoutResolutionResponse from(PayoutResolutionResult result) {
        return new PayoutResolutionResponse(result.resolutionId(), result.resolutionRequestId(), result.failureId(),
                result.orderId(), result.orderItemId(), result.sellerId(), result.eventType(), result.amount(),
                result.reason(), result.category(), result.detail(), result.failedAt(), result.method(),
                result.adminId(), result.resolvedAt(), result.memo());
    }
}
