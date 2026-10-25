package com.ok.payout.controller;

import com.ok.payout.controller.dto.PayoutAdjustmentCreateRequest;
import com.ok.payout.controller.dto.PayoutCandidateResponse;
import com.ok.payout.controller.dto.PayoutCandidateSearchRequest;
import com.ok.payout.controller.dto.PayoutDetailResponse;
import com.ok.payout.controller.dto.PayoutItemResponse;
import com.ok.payout.controller.dto.PayoutItemSearchRequest;
import com.ok.payout.controller.dto.PayoutPageResponse;
import com.ok.payout.controller.dto.PayoutRunResponse;
import com.ok.payout.controller.dto.PayoutSearchRequest;
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
 * PayoutAdminController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Payout (Admin)", description = "관리자 정산 API")
interface PayoutAdminControllerSpec {

    @Operation(summary = "정산 목록 조회", description = """
            모든 정산을 확정 시각 최신순으로 조회합니다(같은 시각이면 정산 ID 역순).
            payeeId를 생략하면 전체 정산이, system으로 시스템 몫만·판매자 몫만 고를 수 있습니다.
            기간(from, to)은 필수이며 정산 확정 시각 기준으로 from은 포함, to는 포함하지 않습니다. 기간은 최대 1년입니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (기간 누락, 시작 시각이 종료 시각보다 같거나 늦음, 기간 1년 초과, payeeId·page·size 범위 등)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutSummaryResponse>> search(@ParameterObject PayoutSearchRequest request);

    @Operation(summary = "정산 상세 조회", description = """
            정산 1건의 상세(정산 대상 기간, 항목 수)를 조회합니다. 항목 목록은 GET /api/v1/admin/payouts/{payoutId}/items로 따로 조회합니다.
            판매자 정산이면 그 판매자 판매에서 떼어 간 수수료 합계·건수와, 그 수수료가 들어 있는 시스템 몫 정산 ID를 함께 줍니다.
            시스템 몫 정산을 열면 이 세 필드는 null입니다. 그 정산이 모든 판매자 수수료를 모은 총합이라 총합은 amount·itemCount에 있고,
            판매자별 수수료는 항목 조회(GET /api/v1/admin/payouts/{payoutId}/items)의 sellerId로 확인합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
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
    ResponseEntity<PayoutDetailResponse> getDetail(
            @Parameter(description = "정산 ID", example = "12") Long payoutId
    );

    @Operation(summary = "정산 항목 조회", description = """
            정산 1건에 묶인 항목을 정산후보 정보와 함께 항목 ID 순서로 조회합니다.
            조정 건이면 조정 정보(조정 번호, 조정한 관리자 ID, 사유)를 함께 줍니다.
            조정 사유는 관리자가 입력한 원문 그대로이므로 화면에 출력할 때 HTML 이스케이프가 필요합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
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
    ResponseEntity<PayoutPageResponse<PayoutItemResponse>> getItems(
            @Parameter(description = "정산 ID", example = "12") Long payoutId,
            @ParameterObject PayoutItemSearchRequest request
    );

    @Operation(summary = "정산후보 조회", description = """
            정산후보(구매확정으로 생긴 정산 대상)를 후보 생성 시각 최신순으로 조회합니다(같은 시각이면 후보 ID 역순).
            sellerId를 넣으면 그 판매자의 후보만, 생략하면 전체 판매자의 후보가 나옵니다.
            정산 전인 후보와 정산이 끝난 후보가 함께 나오며, settled와 payoutId로 구분합니다. unsettledOnly=true면 정산 전인 후보만 나옵니다.
            기간(from, to)은 필수이며 후보 생성 시각 기준으로 from은 포함, to는 포함하지 않습니다. 기간은 최대 1년입니다.
            후보 생성 시각은 평소 건이면 구매확정 직후, 관리자 조정 건이면 조정한 시각입니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (기간 누락, 시작 시각이 종료 시각보다 같거나 늦음, 기간 1년 초과, sellerId·page·size 범위 등)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutCandidateResponse>> searchCandidates(
            @ParameterObject PayoutCandidateSearchRequest request
    );

    @Operation(summary = "정산 전체 수동 실행", description = """
            정산 배치를 지금 1회 실행합니다. 평소 정산은 스케줄러가 실행하며, 이 API는 관리자가 바로 1회 실행할 때 씁니다.
            관리자만 실행할 수 있습니다.
            배치가 COMPLETED가 아니면 서버 오류(500)로 응답합니다. 실행 ID와 배치 상태는 서버 로그에 남습니다.
            실패 기록과 연결한 정식 응답은 #67에서 바뀝니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "배치 실행 성공"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "500", description = "배치 실행 실패 (실행 ID와 배치 상태는 서버 로그에 남음)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutRunResponse> run() throws Exception;

    @Operation(summary = "정산 1건 수동 실행 (막아 둠)", description = """
            관리자 전용 기능이지만 재처리 정책이 정해지지 않아 막아 두었습니다. 누가 요청해도 실행되지 않습니다.
            로그인하지 않았으면 401, 관리자가 아니면 403, 관리자면 501입니다.
            """)
    @ApiResponses({
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "501", description = "아직 지원하지 않는 기능 (관리자 요청)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "주문 항목 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<Void> runOrderItem(
            @Parameter(description = "주문 항목 ID", example = "900") Long orderItemId
    );

    @Operation(summary = "정산 수동 조정 (막아 둠)", description = """
            관리자 전용 기능이지만 조정 번호 발급 방식이 정해지지 않아 막아 두었습니다. 누가 요청해도 조정되지 않습니다.
            로그인하지 않았으면 401, 요청 형식이 틀리면 422가 먼저이고, 형식이 맞으면 관리자는 501, 관리자가 아니면 403입니다.
            조정한 관리자 ID는 요청 바디가 아니라 로그인 토큰에서 받습니다.
            """)
    @ApiResponses({
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (금액 0원, 사유 공백·255자 초과, 지원하지 않는 항목 종류 등)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "501", description = "아직 지원하지 않는 기능 (관리자 요청)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "요청 본문을 읽을 수 없음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<Void> createAdjustment(
            @Parameter(hidden = true) Long memberId,
            PayoutAdjustmentCreateRequest request
    );
}
