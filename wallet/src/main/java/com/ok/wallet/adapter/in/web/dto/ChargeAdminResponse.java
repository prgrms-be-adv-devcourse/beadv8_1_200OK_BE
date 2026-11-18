package com.ok.wallet.adapter.in.web.dto;

import com.ok.wallet.application.dto.ChargeAdminResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "관리자가 보는 충전 건")
public record ChargeAdminResponse(
        @Schema(description = "충전 번호", example = "CHG_3f2b1c9e-8a4d-4e6f-9b1a-2c3d4e5f6a7b") String chargeNo,
        @Schema(description = "회원 ID") Long memberId,
        @Schema(description = "충전 금액", example = "10000") long amount,
        @Schema(description = "충전 상태", example = "IN_PROGRESS",
                allowableValues = {"PENDING", "IN_PROGRESS", "COMPLETED", "FAILED"}) String status,
        @Schema(description = "실패 코드. 실패한 건만 값이 있다", example = "PAYMENT_CANCELED") String failureCode,
        @Schema(description = "실패 사유. 실패한 건만 값이 있다") String failureMessage,
        @Schema(description = "복구가 토스 답만으로 정하지 못해 멈춘 처리 중 건이면 true") boolean heldForReview,
        @Schema(description = "충전 요청 시각") LocalDateTime createdAt,
        @Schema(description = "실패로 처리된 뒤 토스가 승인한 것을 안 시각. 값이 있고 결제 취소 시각이 없으면 결제를 취소할 대상이다")
        LocalDateTime approvedAfterFailureAt,
        @Schema(description = "관리자가 토스 결제 취소를 요청한 시각. 처리 중 건에 값이 있으면 취소 결과를 모르는 건이라 결제 취소만 다시 할 수 있다")
        LocalDateTime paymentCancelRequestedAt,
        @Schema(description = "관리자가 토스 결제를 취소(환불)한 시각") LocalDateTime paymentCanceledAt
) {
    public static ChargeAdminResponse from(ChargeAdminResult result) {
        return new ChargeAdminResponse(
                result.chargeNo(),
                result.memberId(),
                result.amount(),
                result.status(),
                result.failureCode(),
                result.failureMessage(),
                result.heldForReview(),
                result.createdAt(),
                result.approvedAfterFailureAt(),
                result.paymentCancelRequestedAt(),
                result.paymentCanceledAt());
    }
}
