package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutSearchQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Schema(description = "정산 목록 조회 조건")
public record PayoutSearchRequest(

        @Schema(description = "수취인(판매자) 회원 ID. 관리자는 생략하면 전체, 판매자는 반드시 본인 ID를 넣어야 함", example = "7")
        @Positive(message = "수취인 ID는 1 이상이어야 합니다.")
        Long payeeId,

        @Schema(description = "true면 시스템 몫만, false면 판매자 몫만, 생략하면 둘 다", example = "false")
        Boolean system,

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
    // 조회 기간 최대 길이. 기간 없이(또는 아주 길게) 조회하면 전체 개수를 세는 쿼리가 정산 테이블 전체를 훑어 느려지므로 상한을 둠
    // 1년은 우선 정한 값이고, 정산 데이터 양이나 화면 요구에 따라 차후 조정 가능 (바꾸면 isPeriodWithinMaxRange의 메시지와 Swagger 설명도 함께)
    private static final int MAX_PERIOD_YEARS = 1;

    /**
     * 생략된 페이지 번호와 크기에 기본값을 채운다. 검증은 기본값을 채운 값을 기준으로 수행된다.
     */
    public PayoutSearchRequest {
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
    // from·to가 null이면 @NotNull이 따로 잡으므로 여기서는 통과
    @Schema(hidden = true)
    @AssertTrue(message = "조회 기간은 최대 1년입니다.")
    public boolean isPeriodWithinMaxRange() {
        return from == null || to == null || !to.isAfter(from.plusYears(MAX_PERIOD_YEARS));
    }

    public PayoutSearchQuery toQuery() {
        return new PayoutSearchQuery(payeeId, system, from, to, page, size);
    }
}
