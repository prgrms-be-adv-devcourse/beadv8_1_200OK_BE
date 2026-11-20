package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutCandidateSearchQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

// 관리자 정산후보 목록 조회 조건. 판매자용은 PayoutSellerCandidateSearchRequest
// 기간·페이지 규칙(필수, 최대 1년, 기본값, 상한)은 정산 목록 요청(PayoutSearchRequest, PayoutSellerSearchRequest)과 같음. 한쪽을 바꾸면 함께 바꿈
//   예외 (#723): 이 요청만 주문 ID를 주면 기간이 없어도 됨. 막힌 주문을 조정할 때 그 주문의 후보를 대기 중까지 다 보려고
//   주문 하나의 후보는 몇 줄뿐이라(order_id로 시작하는 유니크 인덱스) 기간 없이도 전체를 읽지 않음. 판매자용 요청은 그대로 기간 필수
// TODO(논의 필요): 같은 기간·페이지 검증이 정산 목록·정산후보 요청 4곳에 복사되어 있음 (#198 리뷰 지적)
//  #236 PR이 끝나면 공용으로 모을지 별도 이슈로 정함. 지금은 기존 방식("함께 바꿈" 주석)을 따름
@Schema(description = "관리자 정산후보 목록 조회 조건")
public record PayoutCandidateSearchRequest(

        @Schema(description = "판매자 회원 ID. 생략하면 전체 판매자", example = "7")
        @Positive(message = "판매자 ID는 1 이상이어야 합니다.")
        Long sellerId,

        @Schema(description = "주문 ID. 주면 기간(from, to) 없이 그 주문의 후보 전부(대기 중·정산됨·조정 포함). 기간과 함께 주면 둘 다 적용", example = "100")
        @Positive(message = "주문 ID는 1 이상이어야 합니다.")
        Long orderId,

        @Schema(description = "조회 시작 시각 (orderId가 없으면 필수. 정산후보 생성 시각 기준, 이 시각 포함). 평소 건은 구매확정 시각, 조정 건은 조정한 시각",
                example = "2026-10-01T00:00:00")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime from,

        @Schema(description = "조회 종료 시각 (orderId가 없으면 필수. 정산후보 생성 시각 기준, 이 시각 미포함). 시작부터 최대 1년",
                example = "2026-11-01T00:00:00")
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
    public PayoutCandidateSearchRequest {
        unsettledOnly = unsettledOnly != null && unsettledOnly;
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }

    // 주문 ID가 없으면 기간 둘 다 필수 (#723): 주문 ID가 있을 때만 풀어야 해서 필드의 @NotNull이 아니라 클래스 단위 검사로 둠
    // 주문 ID가 있으면 기간은 하나만 줘도 됨: 준 쪽만 걸어 그 주문의 후보를 더 좁힘
    @Schema(hidden = true)
    @AssertTrue(message = "주문 ID가 없으면 조회 시작 시각과 종료 시각은 필수입니다.")
    public boolean isPeriodPresentWithoutOrderId() {
        return orderId != null || (from != null && to != null);
    }

    // 시작이 종료와 같거나 늦으면 결과가 항상 0건이라 클라이언트 실수로 보고 거절
    // from·to가 null이면 위 검사가 따로 잡으므로 여기서는 통과시켜 오류 메시지가 두 번 나오지 않게 함
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

    public PayoutCandidateSearchQuery toQuery() {
        return new PayoutCandidateSearchQuery(sellerId, orderId, from, to, unsettledOnly, page, size);
    }
}
