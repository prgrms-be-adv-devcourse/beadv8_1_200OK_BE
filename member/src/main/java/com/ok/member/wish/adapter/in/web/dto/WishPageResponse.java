package com.ok.member.wish.adapter.in.web.dto;

import com.ok.member.wish.application.dto.WishResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "내 관심상품 목록")
public record WishPageResponse(

        @Schema(description = "현재 페이지의 찜 (최근에 찜한 것부터)")
        List<WishResponse> content,

        @Schema(description = "현재 페이지 번호 (0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "해제하지 않은 찜 수. 숨김·삭제된 상품의 찜도 포함한다", example = "37")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "2")
        int totalPages
) {

    // Spring Data의 Page를 그대로 직렬화하면 내부 구조(pageable, sort 등)가 응답 계약이 되므로 필요한 값만 옮긴다
    public static WishPageResponse from(Page<WishResult> page) {
        return new WishPageResponse(
                page.getContent().stream().map(WishResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

}
