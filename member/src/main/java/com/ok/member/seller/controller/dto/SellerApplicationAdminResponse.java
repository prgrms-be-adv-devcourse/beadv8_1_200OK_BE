package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationAdminResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "관리자 판매자 신청 상세")
public record SellerApplicationAdminResponse(

        @Schema(description = "판매자 신청 id", example = "1")
        Long applicationId,

        @Schema(description = "신청한 회원 id", example = "10")
        Long memberId,

        @Schema(description = "신청 상태 (PENDING: 심사 대기, IN_REVIEW: 심사 중, APPROVED: 승인, REJECTED: 거절)",
                example = "IN_REVIEW")
        String status,

        @Schema(description = "상호명", example = "청송 햇살농원")
        String storeName,

        @Schema(description = "대표자명", example = "김영수")
        String representativeName,

        @Schema(description = "사업자등록번호", example = "512-81-04273")
        String businessNumber,

        @Schema(description = "통신판매업 신고번호", example = "제2026-경북청송-0031호")
        String mailOrderNumber,

        @Schema(description = "신청 일시", example = "2026-10-18T14:30:00")
        LocalDateTime appliedAt,

        @Schema(description = "심사 시작 일시. 심사 전이면 null", example = "2026-10-19T09:00:00", nullable = true)
        LocalDateTime reviewStartedAt,

        @Schema(description = "승인 일시. 승인 전이면 null", nullable = true)
        LocalDateTime approvedAt,

        @Schema(description = "거절 일시. 거절되지 않았으면 null", nullable = true)
        LocalDateTime rejectedAt,

        @Schema(description = "거절 사유. 거절되지 않았으면 null", nullable = true)
        String rejectReason,

        @Schema(description = "승인·거절을 결정한 관리자 회원 id. 결정 전이면 null", nullable = true)
        Long reviewerId
) {

    public static SellerApplicationAdminResponse from(SellerApplicationAdminResult result) {
        return new SellerApplicationAdminResponse(
                result.applicationId(),
                result.memberId(),
                result.status(),
                result.storeName(),
                result.representativeName(),
                result.businessNumber(),
                result.mailOrderNumber(),
                result.appliedAt(),
                result.reviewStartedAt(),
                result.approvedAt(),
                result.rejectedAt(),
                result.rejectReason(),
                result.reviewerId()
        );
    }
}
