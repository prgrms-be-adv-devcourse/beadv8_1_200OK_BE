package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.OrderSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "주문 목록")
public record OrderSummaryPageResponse(
        @Schema(description = "현재 페이지의 주문 (최신순)") List<OrderSummaryResponse> content,
        @Schema(description = "현재 페이지 번호 (0부터)", example = "0") int page,
        @Schema(description = "페이지 크기", example = "10") int size,
        @Schema(description = "전체 주문 수", example = "23") long totalElements,
        @Schema(description = "전체 페이지 수", example = "3") int totalPages
) {
    public static OrderSummaryPageResponse from(Page<OrderSummaryResult> page) {
        return new OrderSummaryPageResponse(
                page.getContent().stream().map(OrderSummaryResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}