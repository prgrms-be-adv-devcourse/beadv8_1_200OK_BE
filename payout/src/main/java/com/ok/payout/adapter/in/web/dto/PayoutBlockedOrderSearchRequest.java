package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutBlockedOrderSearchQuery;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

// 페이지 조건은 PayoutResolutionSearchRequest와 같지만, 그 DTO의 Swagger 설명이 "해결 기록 조회 조건"이라 따로 둠
// 기간 조건이 없는 이유: "지금 막힌 주문" 목록이라 기간 밖으로 밀린 오래된 주문이 바로 찾아야 할 대상 (다른 관리자 목록은 기간 필수)
// 주문 ID·상태 조건(#723): 조정한 뒤 그 줄을 다시 찾거나 아직 안 푼 것만 보려고. 둘 다 선택이라 안 주면 예전과 같음
@Schema(description = "막힌 주문 목록 조회 조건")
public record PayoutBlockedOrderSearchRequest(

        @Schema(description = "주문 ID. 주면 그 주문 줄만 (막힌 주문이 아니면 빈 목록)", example = "100")
        @Positive(message = "주문 ID는 1 이상이어야 합니다.")
        Long orderId,

        // 상태 값은 문자열로 받아 서비스가 바꿈: 컨트롤러·DTO는 domain 타입(PayoutBlockedStatus)을 쓰지 않음 (api.md)
        // 예비(PENDING)는 아직 막힌 주문이 아니라 받지 않음 (목록에서 늘 빠짐, #570)
        @ArraySchema(arraySchema = @Schema(description = "상태 (여러 개 가능). LOCKED 잠김, STOPPED 멈춤, RESOLVED 해결됨. 생략하면 셋 다",
                example = "[\"LOCKED\", \"STOPPED\"]"),
                schema = @Schema(allowableValues = {"LOCKED", "STOPPED", "RESOLVED"}))
        List<@Pattern(regexp = "LOCKED|STOPPED|RESOLVED", message = "지원하지 않는 막힌 주문 상태입니다.") String> status,

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
     * 생략된 페이지 번호와 크기에 기본값을 채우고, 상태를 생략하면 빈 목록(셋 다)으로 둔다. 검증은 채운 값을 기준으로 수행된다.
     */
    public PayoutBlockedOrderSearchRequest {
        status = status == null ? List.of() : List.copyOf(status);
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    public PayoutBlockedOrderSearchQuery toQuery() {
        return new PayoutBlockedOrderSearchQuery(orderId, status, page, size);
    }
}
