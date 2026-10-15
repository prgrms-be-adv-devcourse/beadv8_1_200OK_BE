package com.ok.payout.controller;

import com.ok.payout.controller.dto.PayoutAdjustmentCreateRequest;
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
 * PayoutController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Payout", description = "정산 API (개발용, prod 미등록. 인증 전까지 임시 헤더로 회원 정보를 받음)")
interface PayoutControllerSpec {

    @Operation(summary = "정산 전체 수동 실행", description = """
            정산 배치를 지금 1회 실행합니다. 개발 중 수동 실행용이며 prod 정산은 스케줄러로만 실행합니다.
            지금은 권한을 확인하지 않습니다(#52).
            배치가 COMPLETED가 아니면 실패 응답을 줍니다. 실패 기록과 연결한 정식 응답은 #67에서 바뀝니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "배치 실행 성공"),
            @ApiResponse(
                    responseCode = "500", description = "배치 실행 실패 (detail에 실행 ID와 배치 상태)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutRunResponse> run() throws Exception;

    @Operation(summary = "정산 1건 수동 실행 (막아 둠)", description = """
            관리자 전용 기능이지만 재처리 정책이 정해지지 않아 막아 두었습니다. 누가 요청해도 실행되지 않습니다.
            관리자면 501, 관리자가 아니면 403입니다.
            """)
    @ApiResponses({
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "501", description = "아직 지원하지 않는 기능 (관리자 요청)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "역할 헤더 누락, 주문 항목 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<Void> runOrderItem(
            @Parameter(description = "요청 회원 역할 (임시, #52). ADMIN, SELLER 외의 값은 권한 없음", example = "ADMIN") String memberRole,
            @Parameter(description = "주문 항목 ID", example = "900") Long orderItemId
    );

    @Operation(summary = "정산 수동 조정 (막아 둠)", description = """
            관리자 전용 기능이지만 조정 번호 발급 방식이 정해지지 않아 막아 두었습니다. 누가 요청해도 조정되지 않습니다.
            요청 형식 검증(422)이 먼저 이루어지고, 형식이 맞으면 관리자는 501, 관리자가 아니면 403입니다.
            조정한 관리자 ID는 요청 바디가 아니라 회원 ID 헤더(인증 후에는 로그인 정보)에서 받습니다.
            """)
    @ApiResponses({
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
                    responseCode = "400", description = "회원 헤더 누락, 요청 본문을 읽을 수 없음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<Void> createAdjustment(
            @Parameter(description = "요청 회원 ID (임시, #52). 조정한 관리자 ID로 기록될 값", example = "1") Long memberId,
            @Parameter(description = "요청 회원 역할 (임시, #52). ADMIN, SELLER 외의 값은 권한 없음", example = "ADMIN") String memberRole,
            PayoutAdjustmentCreateRequest request
    );

    @Operation(summary = "정산 목록 조회", description = """
            정산을 확정 시각 최신순으로 조회합니다(같은 시각이면 정산 ID 역순).
            관리자는 조건을 자유롭게 쓰고, payeeId를 생략하면 전체 정산이 나옵니다.
            판매자는 payeeId에 반드시 본인 ID를 넣어야 합니다. 생략하거나 다른 ID를 넣거나 system=true를 넣으면 403입니다.
            기간(from, to)은 정산 확정 시각 기준이며 from은 포함, to는 포함하지 않습니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "403", description = "판매자가 payeeId를 생략했거나 본인 ID가 아니거나 시스템 몫을 요청, 관리자·판매자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (page·size 범위, 시작 시각이 종료 시각보다 늦음 등)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "회원 헤더 누락",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutSummaryResponse>> search(
            @Parameter(description = "요청 회원 ID (임시, #52). 판매자면 payeeId와 같아야 함", example = "7") Long memberId,
            @Parameter(description = "요청 회원 역할 (임시, #52). ADMIN, SELLER 외의 값은 권한 없음", example = "SELLER") String memberRole,
            @ParameterObject PayoutSearchRequest request
    );

    @Operation(summary = "정산 상세 조회", description = """
            정산 1건의 상세(정산 대상 기간, 항목 수)를 조회합니다. 항목 목록은 GET /api/v1/payouts/{payoutId}/items로 따로 조회합니다.
            관리자는 모든 정산을, 판매자는 본인 정산만 볼 수 있습니다.
            판매자 정산이면 그 판매자 판매에서 떼어 간 수수료 합계·건수를 함께 줍니다. 수수료가 들어 있는 시스템 몫 정산 ID는 관리자에게만 줍니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "403", description = "판매자가 남의 정산이나 시스템 몫 정산을 요청, 관리자·판매자가 아님",
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
                    responseCode = "400", description = "회원 헤더 누락, 정산 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutDetailResponse> getDetail(
            @Parameter(description = "요청 회원 ID (임시, #52)", example = "7") Long memberId,
            @Parameter(description = "요청 회원 역할 (임시, #52). ADMIN, SELLER 외의 값은 권한 없음", example = "SELLER") String memberRole,
            @Parameter(description = "정산 ID", example = "12") Long payoutId
    );

    @Operation(summary = "정산 항목 조회", description = """
            정산 1건에 묶인 항목을 정산후보 정보와 함께 항목 ID 순서로 조회합니다. 권한은 상세 조회와 같습니다.
            조정 건이면 조정 정보를 함께 주며, 판매자에게는 조정 사유만 줍니다(조정 번호·관리자 ID는 null).
            조정 사유는 관리자가 입력한 원문 그대로이므로 화면에 출력할 때 HTML 이스케이프가 필요합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(
                    responseCode = "403", description = "판매자가 남의 정산이나 시스템 몫 정산을 요청, 관리자·판매자가 아님",
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
                    responseCode = "400", description = "회원 헤더 누락, 정산 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutItemResponse>> getItems(
            @Parameter(description = "요청 회원 ID (임시, #52)", example = "7") Long memberId,
            @Parameter(description = "요청 회원 역할 (임시, #52). ADMIN, SELLER 외의 값은 권한 없음", example = "SELLER") String memberRole,
            @Parameter(description = "정산 ID", example = "12") Long payoutId,
            @ParameterObject PayoutItemSearchRequest request
    );
}
