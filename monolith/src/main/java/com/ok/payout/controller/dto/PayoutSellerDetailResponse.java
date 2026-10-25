package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutDetailResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 판매자가 보는 정산 상세. 관리자용(PayoutDetailResponse)에서 시스템 몫 정산 ID(systemPayoutId)를 뺀 응답
// 서비스 결과는 관리자와 같은 PayoutDetailResult를 받고, 판매자에게 보여 주지 않을 값은 여기서 옮기지 않음
@Schema(description = "판매자 정산 상세")
public record PayoutSellerDetailResponse(

        @Schema(description = "정산 ID", example = "12")
        Long payoutId,

        @Schema(description = "이 정산을 확정한 스냅샷 ID", example = "3")
        Long snapshotId,

        @Schema(description = "수취인(판매자) 회원 ID. 판매자 본인", example = "7")
        Long payeeId,

        @Schema(description = "시스템(플랫폼) 몫 정산 여부. 판매자는 본인 정산만 볼 수 있어 항상 false", example = "false")
        boolean system,

        @Schema(description = "정산 금액 (원). 0원 이하일 수 있음", example = "13000")
        Long amount,

        @Schema(description = "정산 확정 시각", example = "2026-10-15T03:00:00")
        LocalDateTime createdAt,

        @Schema(description = "정산 대상 기간 시작 (포함). 첫 정산 실행이면 null", example = "2026-09-27T00:00:00", nullable = true)
        LocalDateTime periodFrom,

        @Schema(description = "정산 대상 기간 끝 (미포함)", example = "2026-09-30T00:00:00")
        LocalDateTime periodTo,

        @Schema(description = "이 정산에 묶인 정산 항목 수. 항목은 GET /api/v1/payouts/me/{payoutId}/items로 조회", example = "2")
        long itemCount,

        @Schema(description = "이 정산에서 떼어 간 판매 수수료 합계 (원). 같은 회차에 수수료 항목이 없으면 null", example = "300", nullable = true)
        Long sellerFeeAmount,

        @Schema(description = "위 수수료 항목 수. sellerFeeAmount와 같은 조건으로 값이 있음", example = "1", nullable = true)
        Long sellerFeeItemCount
) {

    public static PayoutSellerDetailResponse from(PayoutDetailResult result) {
        return new PayoutSellerDetailResponse(result.payoutId(), result.snapshotId(), result.payeeId(), result.system(),
                result.amount(), result.createdAt(), result.periodFrom(), result.periodTo(), result.itemCount(),
                result.sellerFeeAmount(), result.sellerFeeItemCount());
    }
}
