package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationSearchQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "관리자 판매자 신청 목록 조회 조건")
public record SellerApplicationSearchRequest(

        // 컨트롤러 계층은 도메인 enum(SellerApplicationStatus)을 쓸 수 없어 허용 값을 문자열로 검사한다.
        // 상태를 추가하면 여기도 함께 바꾼다
        @Schema(description = "신청 상태 (PENDING, IN_REVIEW, APPROVED, REJECTED). 생략하면 모든 상태",
                example = "PENDING", nullable = true)
        @Pattern(regexp = "PENDING|IN_REVIEW|APPROVED|REJECTED",
                message = "신청 상태는 PENDING, IN_REVIEW, APPROVED, REJECTED 중 하나여야 합니다.")
        String status,

        @Schema(description = "페이지 번호 (0부터, 기본 0)", example = "0")
        @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.")
        Integer page,

        @Schema(description = "페이지 크기 (1~100, 기본 20)", example = "20")
        @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
        @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.")
        Integer size
) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    /**
     * 생략된 값에 기본값을 채운다. 검증은 기본값을 채운 값을 기준으로 수행된다.
     * 상태를 비워 보내면({@code status=}) 생략한 것과 같게 모든 상태를 조회한다.
     */
    public SellerApplicationSearchRequest {
        status = status == null || status.isBlank() ? null : status.strip();
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    public SellerApplicationSearchQuery toQuery() {
        return new SellerApplicationSearchQuery(status, page, size);
    }

}
