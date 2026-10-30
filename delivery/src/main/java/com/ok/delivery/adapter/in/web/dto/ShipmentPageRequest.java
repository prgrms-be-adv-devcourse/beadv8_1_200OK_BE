package com.ok.delivery.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;

// 페이지 크기는 명세로 25건에 고정해 받지 않는다
@Schema(description = "배송 목록 조회 조건")
public record ShipmentPageRequest(

        @Schema(description = "페이지 번호 (0부터, 기본 0). 한 페이지는 25건", example = "0")
        @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.")
        Integer page
) {

    private static final int DEFAULT_PAGE = 0;

    /**
     * 생략된 페이지 번호에 기본값을 채운다. 검증은 기본값을 채운 값을 기준으로 수행된다.
     */
    public ShipmentPageRequest {
        page = page == null ? DEFAULT_PAGE : page;
    }
}
