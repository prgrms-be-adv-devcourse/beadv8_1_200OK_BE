package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutSellerSearchQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

// 판매자 본인 정산 목록 조회 조건
// 관리자용(PayoutSearchRequest)과 달리 수취인(payeeId)·시스템 몫 여부(system)를 받지 않음: 서버가 토큰의 회원 ID·판매자 몫으로 고정함
// 기간·페이지 규칙(필수, 최대 1년, 기본값, 상한)은 관리자용과 같음. 한쪽을 바꾸면 함께 바꿈
@Schema(description = "판매자 정산 목록 조회 조건")
public record PayoutSellerSearchRequest(

        @Schema(description = "조회 시작 시각 (필수. 정산 확정 시각 기준, 이 시각 포함)", example = "2026-10-01T00:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "조회 시작 시각은 필수입니다.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime from,

        @Schema(description = "조회 종료 시각 (필수. 정산 확정 시각 기준, 이 시각 미포함). 시작부터 최대 1년", example = "2026-11-01T00:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "조회 종료 시각은 필수입니다.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime to,

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
    // 조회 기간 최대 길이 (이유는 PayoutSearchRequest.MAX_PERIOD_YEARS 주석 참고)
    private static final int MAX_PERIOD_YEARS = 1;

    /**
     * 생략된 페이지 번호와 크기에 기본값을 채운다. 검증은 기본값을 채운 값을 기준으로 수행된다.
     */
    public PayoutSellerSearchRequest {
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    // 시작이 종료와 같거나 늦으면 결과가 항상 0건이라 클라이언트 실수로 보고 거절
    // from·to가 null이면 @NotNull이 따로 잡으므로 여기서는 통과시켜 오류 메시지가 두 번 나오지 않게 함
    // is로 시작하는 메서드는 Swagger가 요청 필드로 오해해 문서에 나오므로 숨김
    @Schema(hidden = true)
    @AssertTrue(message = "조회 시작 시각은 종료 시각보다 앞이어야 합니다.")
    public boolean isPeriodValid() {
        return from == null || to == null || from.isBefore(to);
    }

    // 조회 기간은 최대 MAX_PERIOD_YEARS년 (to는 미포함이라 시작 + 1년까지 허용)
    @Schema(hidden = true)
    @AssertTrue(message = "조회 기간은 최대 1년입니다.")
    public boolean isPeriodWithinMaxRange() {
        return from == null || to == null || !to.isAfter(from.plusYears(MAX_PERIOD_YEARS));
    }

    public PayoutSellerSearchQuery toQuery() {
        return new PayoutSellerSearchQuery(from, to, page, size);
    }
}
