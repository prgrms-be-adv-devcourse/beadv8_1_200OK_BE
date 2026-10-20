package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutDetailResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "관리자 정산 상세")
public record PayoutDetailResponse(

        @Schema(description = "정산 ID", example = "12")
        Long payoutId,

        @Schema(description = "이 정산을 확정한 스냅샷 ID", example = "3")
        Long snapshotId,

        @Schema(description = "수취인(판매자) 회원 ID. 시스템 몫이면 null", example = "7", nullable = true)
        Long payeeId,

        @Schema(description = "시스템(플랫폼) 몫 정산 여부. 판매 수수료면 true", example = "false")
        boolean system,

        @Schema(description = "정산 금액 (원). 0원 이하일 수 있음", example = "13000")
        Long amount,

        @Schema(description = "정산 확정 시각", example = "2026-10-15T03:00:00")
        LocalDateTime createdAt,

        @Schema(description = "정산 대상 기간 시작 (포함). 첫 정산 실행이면 null", example = "2026-09-27T00:00:00", nullable = true)
        LocalDateTime periodFrom,

        @Schema(description = "정산 대상 기간 끝 (미포함)", example = "2026-09-30T00:00:00")
        LocalDateTime periodTo,

        @Schema(description = "이 정산에 묶인 정산 항목 수. 항목은 GET /api/v1/admin/payouts/{payoutId}/items로 조회", example = "2")
        long itemCount,

        @Schema(description = "같은 스냅샷의 시스템 몫 정산 ID (이 판매자의 수수료 항목이 들어 있음). 판매자 정산이면 값이 있음. "
                + "시스템 몫 정산을 열면 null (그 정산이 바로 수수료 총합이라 가리킬 정산이 없음)",
                example = "14", nullable = true)
        Long systemPayoutId,

        @Schema(description = "이 판매자 판매에서 떼어 간 수수료 합계 (원). 판매자 정산일 때만 값이 있음. "
                + "시스템 몫 정산은 특정 판매자의 정산이 아니라 모든 판매자 수수료를 모은 총합이라 null이며, "
                + "총합은 amount·itemCount에, 판매자별 수수료는 GET /api/v1/admin/payouts/{payoutId}/items의 sellerId로 확인",
                example = "300", nullable = true)
        Long sellerFeeAmount,

        @Schema(description = "위 수수료 항목 수. 판매자 정산일 때만 값이 있음 (시스템 몫 정산이면 null, 이유는 sellerFeeAmount 참고)",
                example = "1", nullable = true)
        Long sellerFeeItemCount
) {

    public static PayoutDetailResponse from(PayoutDetailResult result) {
        return new PayoutDetailResponse(result.payoutId(), result.snapshotId(), result.payeeId(), result.system(),
                result.amount(), result.createdAt(), result.periodFrom(), result.periodTo(), result.itemCount(),
                result.systemPayoutId(), result.sellerFeeAmount(), result.sellerFeeItemCount());
    }
}
