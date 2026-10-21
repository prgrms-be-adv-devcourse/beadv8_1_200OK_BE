package com.ok.delivery.controller.dto;

import com.ok.delivery.service.dto.SellerShipmentSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "판매자 출고 목록")
public record SellerShipmentPageResponse(

        @Schema(description = "현재 페이지의 배송 (최신순)")
        List<SellerShipmentSummaryResponse> content,

        @Schema(description = "현재 페이지 번호 (0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "25")
        int size,

        @Schema(description = "전체 배송 수", example = "3")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "1")
        int totalPages
) {

    // Spring Data의 Page를 그대로 직렬화하면 내부 구조(pageable, sort 등)가 응답 계약이 되므로 필요한 값만 옮긴다
    public static SellerShipmentPageResponse from(Page<SellerShipmentSummaryResult> page) {
        return new SellerShipmentPageResponse(
                page.getContent().stream().map(SellerShipmentSummaryResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
