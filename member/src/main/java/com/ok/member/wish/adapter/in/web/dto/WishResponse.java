package com.ok.member.wish.adapter.in.web.dto;

import com.ok.member.wish.application.dto.WishResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "찜 한 건")
public record WishResponse(

        @Schema(description = "찜한 상품 ID. 상품 카드 여러 개 조회 API에 넘긴다", example = "101")
        Long productId,

        @Schema(description = "찜한 시각. 해제 후 다시 찜했으면 다시 찜한 시각", example = "2026-11-20T12:40:00")
        LocalDateTime wishedAt
) {

    public static WishResponse from(WishResult result) {
        return new WishResponse(result.productId(), result.wishedAt());
    }

}
