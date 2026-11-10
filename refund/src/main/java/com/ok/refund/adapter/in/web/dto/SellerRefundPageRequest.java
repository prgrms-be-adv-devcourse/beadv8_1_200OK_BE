package com.ok.refund.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

// 페이지 크기는 배송 목록과 같은 25건으로 고정해 받지 않는다
@Schema(description = "판매자 환불 요청 목록 조회 조건")
public record SellerRefundPageRequest(

        @Schema(description = "페이지 번호 (0부터, 기본 0). 한 페이지는 25건", example = "0")
        @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.")
        Integer page,

        // 컨트롤러는 도메인 enum을 쓰지 않으므로 허용 값을 여기서 문자열로 검사한다.
        // 상태가 늘면(#414) 함께 바꾼다. 빠뜨리면 SellerRefundPageRequestTest가 깨진다
        @Schema(description = "상태 거르기 (생략하면 전체). REQUESTED, PROCESSING, REJECTED, WITHDRAWN", example = "REQUESTED")
        @Pattern(regexp = "REQUESTED|PROCESSING|REJECTED|WITHDRAWN",
                message = "상태는 REQUESTED, PROCESSING, REJECTED, WITHDRAWN 중 하나여야 합니다.")
        String status
) {

    private static final int DEFAULT_PAGE = 0;

    /**
     * 생략된 페이지 번호에 기본값을 채운다. 검증은 기본값을 채운 값을 기준으로 수행된다.
     */
    public SellerRefundPageRequest {
        page = page == null ? DEFAULT_PAGE : page;
    }
}
