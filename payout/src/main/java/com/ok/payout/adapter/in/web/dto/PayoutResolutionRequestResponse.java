package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutResolutionRequestResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "해결 요청 1번 (같은 요청 번호의 해결 기록을 한 줄로 묶음)")
public record PayoutResolutionRequestResponse(

        @Schema(description = "해결 요청 번호 (UUID). 1줄씩 보기에서 같은 번호의 줄이 이 요청의 해결 기록",
                example = "3f2b8c1e-7d4a-4b9e-9c21-5a6d8e0f1b23")
        String resolutionRequestId,

        @Schema(description = "주문 ID", example = "100")
        Long orderId,

        @Schema(description = "해결 방법", example = "REPROCESS")
        String method,

        @Schema(description = "해결한 관리자 회원 ID", example = "1")
        Long adminId,

        @Schema(description = "해결 시각 (그 요청에서 가장 이른 해결 기록의 시각)", example = "2026-11-14T10:00:00")
        LocalDateTime resolvedAt,

        @Schema(description = "해결 메모. 관리자가 입력한 원문이라 화면에 출력할 때 HTML 이스케이프가 필요합니다",
                example = "차감액을 9,000원으로 바로잡음")
        String memo,

        @Schema(description = "이 요청으로 해결한 실패 기록 수", example = "3")
        int resolvedFailureCount
) {

    public static PayoutResolutionRequestResponse from(PayoutResolutionRequestResult result) {
        return new PayoutResolutionRequestResponse(result.resolutionRequestId(), result.orderId(), result.method(),
                result.adminId(), result.resolvedAt(), result.memo(), result.resolvedFailureCount());
    }
}
