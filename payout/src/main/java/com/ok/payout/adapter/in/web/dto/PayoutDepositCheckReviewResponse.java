package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutDepositCheckReviewResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "대사에서 관리자 확인 필요가 된 정산 1건")
public record PayoutDepositCheckReviewResponse(

        @Schema(description = "정산 ID. 상세는 GET /api/v1/admin/payouts/{payoutId}", example = "14")
        Long payoutId,

        @Schema(description = "정산이 기대한 판매자 회원 ID", example = "7")
        Long payeeId,

        @Schema(description = "정산 금액(원)", example = "9700")
        Long amount,

        @Schema(description = "지갑이 입금 있음으로 답한 판매자 회원 ID. 입금 없음·지갑 답 없음이면 null",
                example = "8", nullable = true)
        Long walletPayeeId,

        @Schema(description = "지갑이 입금 있음으로 답한 금액(원). 입금 없음·지갑 답 없음이면 null",
                example = "9700", nullable = true)
        Long walletAmount,

        @Schema(description = "관리자 확인 필요 이유. NOT_FOUND 3번 물을 때까지 입금이 확인되지 않았고 "
                + "그중 한 번 이상 지갑이 '없음'으로 답함(나머지는 답이 없었을 수 있음, notFoundCount 참고), "
                + "NO_RESPONSE 3번 물을 때까지 지갑이 한 번도 답하지 않음(지갑 수신 장애일 수 있음), "
                + "PAYEE_MISMATCH 입금은 있는데 판매자가 다름, AMOUNT_MISMATCH 같은 판매자에게 입금은 있는데 금액이 다름",
                example = "PAYEE_MISMATCH",
                allowableValues = {"NOT_FOUND", "NO_RESPONSE", "PAYEE_MISMATCH", "AMOUNT_MISMATCH"})
        String reason,

        @Schema(description = "지갑에 물어본 횟수 (최대 3)", example = "1")
        int attemptCount,

        @Schema(description = "지갑이 '없음'으로 답한 횟수. 물어본 횟수보다 적으면 나머지는 답이 없었거나 '있음'으로 답한 것",
                example = "0")
        int notFoundCount,

        @Schema(description = "지갑에 마지막으로 물으려 한 시각. 보내기에 실패한 시도의 시각일 수 있음. 답을 받은 시각이 아님",
                example = "2026-11-15T02:00:00")
        LocalDateTime lastAskedAt
) {

    public static PayoutDepositCheckReviewResponse from(PayoutDepositCheckReviewResult result) {
        return new PayoutDepositCheckReviewResponse(result.payoutId(), result.payeeId(), result.amount(),
                result.walletPayeeId(), result.walletAmount(), result.reason(), result.attemptCount(),
                result.notFoundCount(), result.lastAskedAt());
    }
}
