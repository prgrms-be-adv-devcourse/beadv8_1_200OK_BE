package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationAdminResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "관리자 판매자 신청 목록의 한 건")
public record SellerApplicationAdminSummaryResponse(

        @Schema(description = "판매자 신청 id", example = "1")
        Long applicationId,

        @Schema(description = "신청한 회원 id", example = "10")
        Long memberId,

        @Schema(description = "상호명", example = "청송 햇살농원")
        String storeName,

        @Schema(description = "대표자명", example = "김영수")
        String representativeName,

        @Schema(description = "사업자등록번호", example = "512-81-04273")
        String businessNumber,

        @Schema(description = "신청 상태 (PENDING: 심사 대기, IN_REVIEW: 심사 중, APPROVED: 승인, REJECTED: 거절)",
                example = "PENDING")
        String status,

        @Schema(description = "신청 일시", example = "2026-10-18T14:30:00")
        LocalDateTime appliedAt
) {

    public static SellerApplicationAdminSummaryResponse from(SellerApplicationAdminResult result) {
        return new SellerApplicationAdminSummaryResponse(
                result.applicationId(),
                result.memberId(),
                result.storeName(),
                result.representativeName(),
                result.businessNumber(),
                result.status(),
                result.appliedAt()
        );
    }

}
