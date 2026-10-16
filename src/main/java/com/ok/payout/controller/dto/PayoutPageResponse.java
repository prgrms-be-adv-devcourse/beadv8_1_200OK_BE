package com.ok.payout.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "정산 페이지 응답 (정산 목록, 정산 항목 조회에서 사용)")
public record PayoutPageResponse<T>(

        @Schema(description = "현재 페이지의 정산 또는 정산 항목")
        List<T> content,

        @Schema(description = "현재 페이지 번호 (0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 항목 수", example = "153")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "8")
        int totalPages
) {

    // Spring Data의 Page를 그대로 응답하면 내부 구조(pageable, sort 등)까지 JSON에 나가고,
    // Spring 버전이 바뀌어 Page 구조가 달라지면 API 응답 모양도 함께 바뀜
    // 응답 모양을 이 DTO가 정하도록 필요한 다섯 값만 옮김
    public static <T> PayoutPageResponse<T> from(Page<T> page) {
        return new PayoutPageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
