package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutResolutionSearchQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

// 페이지 조건만 있는 점은 PayoutItemSearchRequest와 같지만, 그 DTO의 Swagger 설명이 "정산 항목 조회 조건"이라 따로 둠
@Schema(description = "해결 기록 조회 조건")
public record PayoutResolutionSearchRequest(

        @Schema(description = "페이지 번호 (0부터, 기본 0)", example = "0")
        @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.")
        Integer page,

        @Schema(description = "페이지 크기 (1~100, 기본 20)", example = "20")
        @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
        @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.")
        Integer size
) {

    // 다른 관리자 목록(PayoutItemSearchRequest 등)과 같은 기본값·상한. 한쪽을 바꾸면 함께 바꿈
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    /**
     * 생략된 페이지 번호와 크기에 기본값을 채운다. 검증은 기본값을 채운 값을 기준으로 수행된다.
     */
    public PayoutResolutionSearchRequest {
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    public PayoutResolutionSearchQuery toQuery() {
        return new PayoutResolutionSearchQuery(page, size);
    }
}
