package com.ok.payout.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "정산 수동 실행 응답")
public record PayoutRunResponse(

        @Schema(description = "배치 실행 ID (Spring Batch JobExecution ID)", example = "1")
        Long executionId,

        @Schema(description = "배치 실행 상태", example = "COMPLETED")
        String status
) {
}
