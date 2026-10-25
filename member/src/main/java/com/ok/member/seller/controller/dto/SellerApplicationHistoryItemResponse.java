package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "판매자 신청 내역 한 건 (심사 진행 일시)")
public record SellerApplicationHistoryItemResponse(

        @Schema(description = "판매자 신청 id. 신청 정보 조회에 쓴다", example = "1")
        Long applicationId,

        @Schema(description = "신청 상태 (PENDING: 심사 대기, IN_REVIEW: 심사 중, APPROVED: 승인, REJECTED: 거절)",
                example = "APPROVED")
        String status,

        @Schema(description = "신청 일시", example = "2026-10-18T14:30:00")
        LocalDateTime appliedAt,

        @Schema(description = "심사 시작 일시. 심사 전이면 null", example = "2026-10-19T09:00:00", nullable = true)
        LocalDateTime reviewStartedAt,

        @Schema(description = "승인 일시. 승인 전이면 null", example = "2026-10-20T11:00:00", nullable = true)
        LocalDateTime approvedAt,

        @Schema(description = "거절 일시. 거절되지 않았으면 null", nullable = true)
        LocalDateTime rejectedAt,

        @Schema(description = "거절 사유. 거절되지 않았으면 null", example = "통신판매업 신고번호를 확인할 수 없습니다.",
                nullable = true)
        String rejectReason
) {

    public static SellerApplicationHistoryItemResponse from(SellerApplicationResult result) {
        return new SellerApplicationHistoryItemResponse(
                result.applicationId(),
                result.status(),
                result.appliedAt(),
                result.reviewStartedAt(),
                result.approvedAt(),
                result.rejectedAt(),
                result.rejectReason()
        );
    }

}
