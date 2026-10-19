package com.ok.wallet.controller.dto;

import com.ok.wallet.service.dto.LedgerHistoryResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

// 페이지 정보는 주문 목록 응답(OrderSummaryPageResponse)과 같은 형식으로 맞춘다
@Schema(description = "이용 내역 목록")
public record LedgerHistoryPageResponse(
        @Schema(description = "현재 페이지의 내역 (최신순)") List<LedgerHistoryResponse> content,
        @Schema(description = "현재 페이지 번호 (0부터)", example = "0") int page,
        @Schema(description = "페이지 크기", example = "10") int size,
        @Schema(description = "전체 내역 수", example = "13") long totalElements,
        @Schema(description = "전체 페이지 수", example = "2") int totalPages
) {
    public static LedgerHistoryPageResponse from(Page<LedgerHistoryResult> page) {
        return new LedgerHistoryPageResponse(
                page.getContent().stream().map(LedgerHistoryResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
