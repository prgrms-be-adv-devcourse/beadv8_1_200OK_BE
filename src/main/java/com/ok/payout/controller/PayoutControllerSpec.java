package com.ok.payout.controller;

import com.ok.payout.controller.dto.PayoutItemSearchRequest;
import com.ok.payout.controller.dto.PayoutPageResponse;
import com.ok.payout.controller.dto.PayoutSellerDetailResponse;
import com.ok.payout.controller.dto.PayoutSellerItemResponse;
import com.ok.payout.controller.dto.PayoutSellerSearchRequest;
import com.ok.payout.controller.dto.PayoutSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * PayoutController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Payout (Seller)", description = "판매자 본인 정산 조회 API (개발용, prod 미등록)")
interface PayoutControllerSpec {

    @Operation(summary = "본인 정산 목록 조회", description = """
            로그인한 판매자 본인의 정산(판매자 몫)을 확정 시각 최신순으로 조회합니다(같은 시각이면 정산 ID 역순).
            누구의 정산인지는 로그인 토큰으로 정하므로 수취인 ID를 보내지 않습니다.
            기간(from, to)은 필수이며 정산 확정 시각 기준으로 from은 포함, to는 포함하지 않습니다. 기간은 최대 1년입니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "판매자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (기간 누락, 시작 시각이 종료 시각보다 같거나 늦음, 기간 1년 초과, page·size 범위 등)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutSummaryResponse>> search(
            @Parameter(hidden = true) Long memberId,
            @ParameterObject PayoutSellerSearchRequest request
    );

    @Operation(summary = "본인 정산 상세 조회", description = """
            본인 정산 1건의 상세(정산 대상 기간, 항목 수)를 조회합니다. 항목 목록은 GET /api/v1/payouts/me/{payoutId}/items로 따로 조회합니다.
            이 정산에서 떼어 간 판매 수수료 합계·건수를 함께 줍니다.
            다른 판매자의 정산이나 시스템 몫 정산 ID를 넣으면 403입니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "판매자가 아님, 본인 정산이 아님(다른 판매자의 정산이나 시스템 몫 정산)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404", description = "정산이 없음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "500", description = "정산의 스냅샷이 없음 (데이터 손상)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "정산 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutSellerDetailResponse> getDetail(
            @Parameter(hidden = true) Long memberId,
            @Parameter(description = "정산 ID", example = "12") Long payoutId
    );

    @Operation(summary = "본인 정산 항목 조회", description = """
            본인 정산 1건에 묶인 항목을 정산후보 정보와 함께 항목 ID 순서로 조회합니다. 권한은 상세 조회와 같습니다.
            관리자가 금액을 직접 고친 조정 건이면 조정 사유를 함께 줍니다.
            조정 사유는 관리자가 입력한 원문 그대로이므로 화면에 출력할 때 HTML 이스케이프가 필요합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "판매자가 아님, 본인 정산이 아님(다른 판매자의 정산이나 시스템 몫 정산)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "404", description = "정산이 없음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (page·size 범위)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "정산 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutSellerItemResponse>> getItems(
            @Parameter(hidden = true) Long memberId,
            @Parameter(description = "정산 ID", example = "12") Long payoutId,
            @ParameterObject PayoutItemSearchRequest request
    );
}
