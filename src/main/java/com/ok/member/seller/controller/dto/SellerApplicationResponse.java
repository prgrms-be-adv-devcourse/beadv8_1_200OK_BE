package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "판매자 신청 정보")
public record SellerApplicationResponse(

        @Schema(description = "판매자 신청 id", example = "1")
        Long applicationId,

        @Schema(description = "신청 상태 (PENDING: 심사 대기, IN_REVIEW: 심사 중, APPROVED: 승인, REJECTED: 거절)",
                example = "PENDING")
        String status,

        @Schema(description = "상호명")
        String storeName,

        @Schema(description = "대표자명")
        String representativeName,

        @Schema(description = "사업자등록번호", example = "512-81-04273")
        String businessNumber,

        @Schema(description = "신청 일시", example = "2026-10-18T14:30:00")
        LocalDateTime appliedAt
) {

    public static SellerApplicationResponse from(SellerApplicationResult result) {
        return new SellerApplicationResponse(
                result.applicationId(),
                result.status(),
                result.storeName(),
                result.representativeName(),
                result.businessNumber(),
                result.appliedAt()
        );
    }

}
