package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationAdminResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "관리자 판매자 신청 목록")
public record SellerApplicationAdminPageResponse(

        @Schema(description = "현재 페이지의 신청 (오래된 신청부터)")
        List<SellerApplicationAdminSummaryResponse> content,

        @Schema(description = "현재 페이지 번호 (0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 신청 수", example = "37")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "2")
        int totalPages
) {

    // Spring Data의 Page를 그대로 직렬화하면 내부 구조(pageable, sort 등)가 응답 계약이 되므로 필요한 값만 옮긴다
    public static SellerApplicationAdminPageResponse from(Page<SellerApplicationAdminResult> page) {
        return new SellerApplicationAdminPageResponse(
                page.getContent().stream().map(SellerApplicationAdminSummaryResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

}
