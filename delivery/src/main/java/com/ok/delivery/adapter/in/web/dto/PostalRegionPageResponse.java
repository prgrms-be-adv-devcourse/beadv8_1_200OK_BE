package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.PostalRegionResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "도서산간 우편번호 목록")
public record PostalRegionPageResponse(

        @Schema(description = "현재 페이지의 우편번호 (우편번호 순)")
        List<PostalRegionResponse> content,

        @Schema(description = "현재 페이지 번호 (0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "25")
        int size,

        @Schema(description = "전체 항목 수", example = "714")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "29")
        int totalPages
) {

    // Spring Data의 Page를 그대로 직렬화하면 내부 구조(pageable, sort 등)가 응답 계약이 되므로 필요한 값만 옮긴다
    public static PostalRegionPageResponse from(Page<PostalRegionResult> page) {
        return new PostalRegionPageResponse(
                page.getContent().stream().map(PostalRegionResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
