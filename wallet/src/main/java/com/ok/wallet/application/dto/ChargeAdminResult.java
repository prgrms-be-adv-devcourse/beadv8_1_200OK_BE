package com.ok.wallet.application.dto;

import com.ok.wallet.domain.Charge;

import java.time.LocalDateTime;

/**
 * 관리자가 보는 충전 건. 상태는 컨트롤러가 그대로 쓸 수 있게 문자열로 내보낸다.
 *
 * @param heldForReview          복구가 멈춘 처리 중 건이면 true
 * @param approvedAfterFailureAt 실패로 처리된 뒤 토스가 승인한 것을 안 시각. 결제를 취소할 대상
 * @param paymentCancelRequestedAt 관리자가 토스 결제 취소를 요청한 시각. 처리 중 건에 값이 있으면 취소 결과를 모르는 건이라 결제 취소만 할 수 있다
 * @param paymentCanceledAt      관리자가 토스 결제를 취소한 시각
 */
public record ChargeAdminResult(
        String chargeNo,
        Long memberId,
        long amount,
        String status,
        String failureCode,
        String failureMessage,
        boolean heldForReview,
        LocalDateTime createdAt,
        LocalDateTime approvedAfterFailureAt,
        LocalDateTime paymentCancelRequestedAt,
        LocalDateTime paymentCanceledAt
) {
    public static ChargeAdminResult from(Charge charge) {
        return new ChargeAdminResult(
                charge.getChargeNo(),
                charge.getMemberId(),
                charge.getAmount(),
                charge.getStatus().name(),
                charge.getFailureCode(),
                charge.getFailureMessage(),
                charge.isHeldForReview(),
                charge.getCreatedAt(),
                charge.getApprovedAfterFailureAt(),
                charge.getPaymentCancelRequestedAt(),
                charge.getPaymentCanceledAt());
    }
}
