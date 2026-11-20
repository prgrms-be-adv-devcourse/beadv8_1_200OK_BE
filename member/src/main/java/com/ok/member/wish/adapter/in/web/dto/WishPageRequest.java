package com.ok.member.wish.adapter.in.web.dto;

import com.ok.member.wish.application.dto.WishSearchQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "내 관심상품 목록 조회 조건")
public record WishPageRequest(

        // 상한이 없으면 page × size가 int를 넘어 JPA가 offset을 거부하고 500이 난다. 찜 개수 상한이 없어 넉넉히 둔다
        @Schema(description = "페이지 번호 (0~10000, 기본 0)", example = "0")
        @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.")
        @Max(value = 10_000, message = "페이지 번호는 10000 이하여야 합니다.")
        Integer page,

        // 최댓값은 상품 카드 여러 개 조회 API(#816)의 ID 개수 상한과 맞춘다. 한 페이지를 카드 조회 한 번으로 채우기 위해서다
        @Schema(description = "페이지 크기 (1~50, 기본 20)", example = "20")
        @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
        @Max(value = 50, message = "페이지 크기는 50 이하여야 합니다.")
        Integer size
) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    /**
     * 생략된 값에 기본값을 채운다. 검증은 기본값을 채운 값을 기준으로 수행된다.
     */
    public WishPageRequest {
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    public WishSearchQuery toQuery() {
        return new WishSearchQuery(page, size);
    }

}
