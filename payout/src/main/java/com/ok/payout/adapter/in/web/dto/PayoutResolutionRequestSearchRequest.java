package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutResolutionRequestSearchQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

// 해결 기록 전체 목록 조회 조건 (#723). 주문별 조회(PayoutResolutionSearchRequest)는 페이지만 있어 따로 둠
// 기간·페이지 규칙(필수, 최대 1년, 기본값, 상한)은 정산 목록 요청(PayoutSearchRequest)과 같음. 한쪽을 바꾸면 함께 바꿈
//   기간 필수인 이유: 해결 기록은 쌓기만 해서, 기간 없이 부르면 운영 시작 이후 기록을 전부 읽음 (payout/CLAUDE.md 설계 전제)
// TODO(논의 필요): 같은 기간·페이지 검증이 정산 목록·정산후보 요청 4곳에 이어 5곳째 복사됨 (#198 리뷰 지적, PayoutCandidateSearchRequest 주석)
//   공용 검증(공통 부모나 검증기)으로 모을지, 모은다면 정산후보 요청의 주문 ID 예외(#723)를 어떻게 둘지 정해야 함
@Schema(description = "해결 기록 전체 목록 조회 조건")
public record PayoutResolutionRequestSearchRequest(

        @Schema(description = "조회 시작 시각 (필수. 해결 시각 기준, 이 시각 포함)", example = "2026-11-16T00:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "조회 시작 시각은 필수입니다.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime from,

        @Schema(description = "조회 종료 시각 (필수. 해결 시각 기준, 이 시각 미포함). 시작부터 최대 1년", example = "2026-11-23T00:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "조회 종료 시각은 필수입니다.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime to,

        @Schema(description = "해결한 관리자 회원 ID. 생략하면 전체 관리자", example = "1")
        @Positive(message = "관리자 ID는 1 이상이어야 합니다.")
        Long adminId,

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
    public PayoutResolutionRequestSearchRequest {
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

    public PayoutResolutionRequestSearchQuery toQuery() {
        return new PayoutResolutionRequestSearchQuery(from, to, adminId, page, size);
    }
}
