package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutSellerCandidateSearchQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

// 판매자 본인 정산후보 목록 조회 조건. 판매자 ID를 받지 않음: 서버가 토큰의 회원 ID로 고정함
// 기간·페이지 규칙(필수, 최대 1년, 기본값, 상한)은 관리자용(PayoutCandidateSearchRequest)과 정산 목록 요청과 같음. 한쪽을 바꾸면 함께 바꿈
// TODO(논의 필요): 같은 기간·페이지 검증이 정산 목록·정산후보 요청 4곳에 복사되어 있음 (#198 리뷰 지적)
//  #236 PR이 끝나면 공용으로 모을지 별도 이슈로 정함. 지금은 기존 방식("함께 바꿈" 주석)을 따름
@Schema(description = "판매자 정산후보 목록 조회 조건")
public record PayoutSellerCandidateSearchRequest(

        @Schema(description = "조회 시작 시각 (필수. 정산후보 생성 시각 기준, 이 시각 포함). 평소 건은 구매확정 시각, 조정 건은 조정한 시각",
                example = "2026-10-01T00:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "조회 시작 시각은 필수입니다.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime from,

        @Schema(description = "조회 종료 시각 (필수. 정산후보 생성 시각 기준, 이 시각 미포함). 시작부터 최대 1년",
                example = "2026-11-01T00:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "조회 종료 시각은 필수입니다.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime to,

        @Schema(description = "true면 아직 정산 전인 후보만, 생략하거나 false면 전부", example = "false")
        Boolean unsettledOnly,

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
    // 조회 기간 최대 길이 (이유는 PayoutSearchRequest.MAX_PERIOD_YEARS 주석 참고. 후보 테이블은 정산보다 커서 상한이 더 필요함)
    private static final int MAX_PERIOD_YEARS = 1;

    /**
     * 생략된 값(미정산만 여부, 페이지 번호·크기)에 기본값을 채운다. 검증은 기본값을 채운 값을 기준으로 수행된다.
     */
    public PayoutSellerCandidateSearchRequest {
        unsettledOnly = unsettledOnly != null && unsettledOnly;
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

    public PayoutSellerCandidateSearchQuery toQuery() {
        return new PayoutSellerCandidateSearchQuery(from, to, unsettledOnly, page, size);
    }
}
