package com.ok.member.seller.adapter.in.web.dto;

import com.ok.member.seller.application.dto.SellerApplicationResult;
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

        @Schema(description = "출고지 우편번호 (5자리 숫자, 신청 당시 값). 판매자 프로필을 처음 등록할 때 기본값으로 쓴다. 이 항목이 생기기 전 신청은 null",
                example = "37421", nullable = true)
        String originPostalCode,

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
                result.originPostalCode(),
                result.appliedAt()
        );
    }

}
