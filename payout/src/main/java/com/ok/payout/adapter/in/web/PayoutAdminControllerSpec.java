package com.ok.payout.adapter.in.web;

import com.ok.payout.adapter.in.web.dto.PayoutAdjustmentCreateRequest;
import com.ok.payout.adapter.in.web.dto.PayoutAdjustmentCreateResponse;
import com.ok.payout.adapter.in.web.dto.PayoutBlockedOrderResponse;
import com.ok.payout.adapter.in.web.dto.PayoutBlockedOrderSearchRequest;
import com.ok.payout.adapter.in.web.dto.PayoutCandidateResponse;
import com.ok.payout.adapter.in.web.dto.PayoutCandidateSearchRequest;
import com.ok.payout.adapter.in.web.dto.PayoutDepositCheckReviewResponse;
import com.ok.payout.adapter.in.web.dto.PayoutDepositCheckReviewSearchRequest;
import com.ok.payout.adapter.in.web.dto.PayoutDetailResponse;
import com.ok.payout.adapter.in.web.dto.PayoutItemResponse;
import com.ok.payout.adapter.in.web.dto.PayoutItemSearchRequest;
import com.ok.payout.adapter.in.web.dto.PayoutOrderResolutionResponse;
import com.ok.payout.adapter.in.web.dto.PayoutOrderResolveRequest;
import com.ok.payout.adapter.in.web.dto.PayoutPageResponse;
import com.ok.payout.adapter.in.web.dto.PayoutResolutionRequestResponse;
import com.ok.payout.adapter.in.web.dto.PayoutResolutionRequestSearchRequest;
import com.ok.payout.adapter.in.web.dto.PayoutResolutionResponse;
import com.ok.payout.adapter.in.web.dto.PayoutResolutionSearchRequest;
import com.ok.payout.adapter.in.web.dto.PayoutRunResponse;
import com.ok.payout.adapter.in.web.dto.PayoutSearchRequest;
import com.ok.payout.adapter.in.web.dto.PayoutSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
@Tag(name = "Payout (Admin)", description = PayoutAdminControllerSpec.ADMIN_TAG_DESCRIPTION)
interface PayoutAdminControllerSpec {

    // 관리자 정산 묶음(@Tag "Payout (Admin)") 설명 (#723): 프런트가 Swagger만 읽고 막힌 주문 화면을 만들 수 있게 처리 흐름을 적음
    // 대기 건너뛰기 실행 Spec(PayoutSkipWaitRunControllerSpec)도 같은 이름의 태그를 써서, 설명이 둘로 갈리지 않게 이 상수를 함께 씀
    String ADMIN_TAG_DESCRIPTION = """
            관리자 정산 API입니다.

            [막힌 주문 처리 흐름]
            정산 배치가 검사하다 문제가 생긴 주문은 정산에서 빠지고 "막힌 주문"이 됩니다. 관리자가 아래 순서로 풉니다.
            - "다시 처리"는 해결 API(POST /api/v1/admin/payouts/orders/{orderId}/resolution)를 부르는 것입니다.
            - "금액 검사"는 다음 정산 실행 때 결제 금액과 정산후보 합계, 판매 대금과 수수료를 비교하는 검사입니다.

            1. 막힌 주문 목록(GET /api/v1/admin/payouts/blocked-orders)에서 주문을 고릅니다. 아직 안 푼 것만 보려면 status=LOCKED&status=STOPPED, 주문 하나만 보려면 orderId를 씁니다.
            2. 막힌 이유(reasons)를 봅니다. AMOUNT_MISMATCH·FEE_MISMATCH가 하나라도 있으면 조정이 필요할 수 있습니다(3~5번, FEE_MISMATCH는 먼저 원인 확인). 그 밖의 이유는 조정하지 않고, 목록 API 설명의 표대로 원인을 확인·조치한 뒤 6번으로 갑니다.
            3. 그 주문의 정산후보를 봅니다: GET /api/v1/admin/payouts/candidates?orderId={orderId}&size=100. 기간 없이 그 주문의 후보가 대기 중·정산됨·조정까지 금액과 함께 나옵니다.
               목록 줄의 원인(causes)과 근거(evidenceAtBlock·evidenceNow의 혼자 넘는 주문상품 aloneOverItems, 이미 지급 settledItems)를 함께 보고 실제로 금액이 틀린 상품을 고릅니다.
               틀린 상품이 아직 대기 중이면 원인에는 없고 정산후보 조회에만 나옵니다. 배송비(SALE_SHIPPING_FEE)는 조정할 수 없습니다(배송비가 틀렸으면 개발자에게 알립니다).
               조정을 넣기 전에 그 줄의 pendingSaleAmountAdjustment·pendingSaleFeeAdjustment를 먼저 봅니다. 0이 아니면 누군가 이미 넣은 조정이 있고, 해결하면 검사에 들어갑니다. 같은 조정을 또 넣지 마세요.
            4. 조정(POST /api/v1/admin/payouts/adjustments)을 넣습니다. 판매 금액을 바꿀 때는 판매 대금(SALE_AMOUNT)과 수수료(SALE_FEE)를 함께 넣습니다.
               판매 금액 = 판매 대금 + 수수료, 수수료 = 판매 금액 × 3%(원 미만 버림)입니다. 예) 맞는 판매 금액이 30,000이면 수수료 900, 판매 대금 29,100.
               맞는 판매 금액은 그 상품의 실제 가격(주문 정보)으로 정합니다. 결제 금액에는 다른 상품·배송비가 들어 있을 수 있어, 결제 금액에 맞추는 값이 아닙니다.
               두 조정을 다 넣은 뒤에 다시 처리합니다(하나만 넣고 풀면 나머지는 검사에 들어가지 않습니다).
            5. 목록을 다시 불러(orderId 조건) 그 줄의 pendingSaleAmountAdjustment·pendingSaleFeeAdjustment로 조정이 들어갔는지 확인합니다.
               초과 청구(AMOUNT_MISMATCH) 줄이면 해결 뒤 예상 합계 = evidenceNow.candidateTotal + 두 조정 합계입니다.
               예상 합계가 결제 금액(evidenceNow.deductedAmount)보다 크면 아직 넘쳐 다음 금액 검사에서 다시 실패합니다(금액 불일치는 3번 실패하면 다시 멈춤). 같거나 작다고 맞게 고친 것은 아닙니다(아직 후보가 없는 상품, 이미 지급된 틀린 상품이 있을 수 있음). 결제 금액과 같게 맞추려고 조정을 더 넣지 마세요.
               수수료 불일치(FEE_MISMATCH)만 있는 줄은 evidenceNow가 없습니다. 상품마다 판매 대금 + 조정과 수수료 + 조정(실패 뒤에 넣은 것)이 위 계산식에 맞는지 정산후보 조회로 봅니다.
               미리 넣은 조정인지는 조정 시각을 원인의 failedAt과 비교해 가리지 마세요. failedAt은 같은 원인 중 가장 늦은 실패 시각이라, 맞게 넣은 조정도 "미리 넣은 것"처럼 보입니다. 대기 합계에 잡히면 미리 넣은 조정이 아닙니다.
            6. 다시 처리로 풉니다. 돈은 바로 나가지 않습니다. 다음 정산 실행(매일 11:00 정기 실행, 관리자 수동 실행도 있음)이 금액 검사부터 다시 하고, 통과하면 정산합니다.
               통과하지 못하면 잠그는 사유(수수료 불일치·결과 검증 불일치)는 그 실행에서 바로 다시 잠깁니다. 그 밖의 사유는 성공한 정산 실행에서 3번 더 실패해야 다시 멈추고, 그 사이에는 목록에서 빠집니다.
               줄이 빠진 뒤 정산됐는지는 정산후보 조회(orderId)의 settled로 봅니다.

            해결됨(RESOLVED) 줄에는 다시 처리 버튼을 두지 않습니다(409). 이 줄에서는 조정도 넣지 마세요. 해결한 뒤에 넣은 조정은 검사에 들어가지 않고 지급에만 들어가, 판매자 돈이 검사 없이 바뀝니다.
            조정이 빠졌다고 보이면 다시 막힌 뒤(LOCKED·STOPPED) 넣습니다. 예외는 실패 전에 미리 넣은 조정을 상쇄할 때뿐입니다(조정 API 설명의 순서).
            막힌 주문 목록이 비어 있다고 정상이라는 뜻은 아닙니다. 잠그지 않는 사유(결제 기록 없음·금액 불일치·예상하지 못한 오류)로 1~2번 실패한 주문은 아직 막힌 주문이 아니라 목록에 없습니다.
            이때는 정산후보 조회(orderId)의 settled·candidateCreatedAt으로 대기 중인지 정산됐는지 봅니다. 1~2번 실패한 기록은 지금 관리자 API로 볼 수 없습니다.
            막힌 주문 하나만 불러오는 별도 API는 없습니다. 목록의 orderId 조건을 씁니다.
            지난 해결을 기간으로 돌아볼 때는 해결 기록 전체 목록(GET /api/v1/admin/payouts/resolution-requests)을 씁니다.
            """;

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
            판매자 정산이면 이 정산이 대금을 치른 주문상품에서 떼어 간 수수료 합계·건수(정산된 수수료 조정 포함, 회차와 상관없이)와, 같은 회차 시스템 몫 정산 ID를 함께 줍니다. 수수료가 없으면 0입니다.
            0원 이하로 보류됐다가 이번에 정산된 대금의 수수료는 앞 회차 시스템 몫 정산에 있어서, 같은 회차 시스템 몫 정산에는 수수료 일부만 있을 수 있습니다.
            그 회차에 시스템 몫 정산이 없으면 systemPayoutId만 null이고, 수수료 합계·건수는 값(없으면 0)입니다.
            지난 정산이라도 그 주문상품의 수수료 조정이 나중 회차에 정산되면 수수료 합계·건수가 바뀝니다(예: 300 → 수수료 조정 −100 정산 뒤 200).
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
            기간(from, to)은 orderId가 없으면 필수이며 후보 생성 시각 기준으로 from은 포함, to는 포함하지 않습니다. 기간은 최대 1년입니다.
            후보 생성 시각은 평소 건이면 구매확정 직후, 관리자 조정 건이면 조정한 시각입니다.
            orderId를 주면 기간 없이 그 주문의 정산후보를 모두 봅니다(대기 중·정산됨·조정 포함). 막힌 주문을 조정할 때 상품별 금액을 보는 용도입니다.
            orderId와 기간을 함께 주면 둘 다 적용합니다. 한 주문이라도 페이지로 나뉘니 size=100을 주거나 모든 페이지를 읽으세요.
            settled=false인 후보에는 15일 대기 중인 상품, 이번 실행 대상인데 아직 정산되지 않은 상품, 막혀서(실패) 정산에서 빠진 상품, 판매자 합계가 0원 이하라 보류된 상품이 함께 있습니다.
            조정 후보(adjustment가 있음)가 금액 검사에 들어가는지는 이 응답만으로 가릴 수 없습니다. 막힌 주문 목록(orderId)의 pendingSaleAmountAdjustment·pendingSaleFeeAdjustment로 봅니다.
            원인의 failedAt은 같은 원인 중 가장 늦은 실패 시각이라, 조정 시각과 비교해 "미리 넣은 조정"을 가리는 기준으로 쓰지 마세요.
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
                    responseCode = "422", description = "요청 값 검증 실패 (orderId도 기간도 없음, 시작 시각이 종료 시각보다 같거나 늦음, 기간 1년 초과, sellerId·orderId·page·size 범위 등). "
                            + "orderId도 기간도 없을 때 오류 목록(errors)의 path는 from·to가 아니라 periodPresentWithoutOrderId 하나",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "시각·숫자 형식이 틀림",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutCandidateResponse>> searchCandidates(
            @ParameterObject PayoutCandidateSearchRequest request
    );

    @Operation(summary = "정산 전체 수동 실행", description = """
            정산 배치를 지금 1회 실행합니다. 평소 정산은 스케줄러가 실행하며, 이 API는 관리자가 바로 1회 실행할 때 씁니다.
            관리자만 실행할 수 있습니다.
            이미 실행 중이면 새로 시작하지 않고 409로 응답합니다.
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
                    responseCode = "409", description = "정산이 이미 실행 중",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "실행 중", value = """
                                    {
                                      "type": "https://api.example.com/problems/payout/payout-job-already-running",
                                      "title": "Payout job already running",
                                      "status": 409,
                                      "detail": "정산이 이미 실행 중입니다.",
                                      "instance": "/api/v1/admin/payouts/run"
                                    }
                                    """))
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

    @Operation(summary = "정산 수동 조정", description = """
            관리자 전용입니다. 금액이 틀린 정산후보를 조정 정산후보(음수면 차감)로 바로잡습니다. 조정은 지울 수 없습니다. 잘못 넣었으면 반대 금액의 조정(사유를 다르게)을 넣어 상쇄하세요.
            조정 번호는 서버가 그 주문 항목·종류의 마지막 번호 + 1로 붙이고, 주문·판매자는 원래 정산후보에서 가져옵니다.
            조정은 원래 정산후보를 따라 정산됩니다. 원래가 아직 15일 대기 중이면 함께 기다리고, 이미 정산됐으면 다음 배치에 바로 들어갑니다.
            판매자의 그 배치 합계가 0원 이하면 그 판매자는 정산하지 않고 다음 매출이 생길 때까지 보류합니다.

            금액 불일치로 막힌 주문을 풀 때는 순서를 지키세요: 실패가 난 뒤 조정을 넣고, 그다음 "다시 처리"로 해결합니다.
            이 순서로 넣은 조정만 금액 검사에 들어갑니다(원래 정산후보와 함께 정산되는 배치에서).
            실패 전에 미리 넣었거나 해결한 뒤에 넣은 조정은 다시 해결해도 검사에 들어가지 않고, 지급에만 들어갑니다.
            조정을 넣기 전에 막힌 주문 목록(orderId)의 pendingSaleAmountAdjustment·pendingSaleFeeAdjustment를 먼저 보세요. 0이 아니면 이미 들어간 조정이 있습니다.
            실패 전에 미리 넣은 조정 A가 있으면(정산후보 조회에는 A가 있는데 대기 합계에는 안 잡힘) 아래 순서로 상쇄합니다.
              1) A는 검사에 들어가지 않으니 A가 없다고 보고 맞는 조정 B를 계산해 넣습니다.
              2) 대기 합계에 B가 잡혔는지 보고 "다시 처리"로 해결합니다.
              3) 해결한 뒤에 A의 반대 금액 조정을 넣습니다. 이 조정은 검사 밖이라 지급에서 A만 지웁니다.
            3)의 반대 조정을 해결 전에 넣으면 그것도 검사에 들어가 다시 막힙니다.
            조정은 실제로 금액이 틀린 주문 항목에 넣으세요. 초과 청구 근거의 "넘게 만든 주문상품"은 언제부터 넘었는지 알려 줄 뿐 틀린 항목이라는 뜻이 아니니, "혼자 넘는 주문상품"·"이미 지급"과 함께 보고 고르세요.
            같은 주문의 다른 항목에 넣어도 검사는 주문 전체 합계로 보아 통과하지만, 다른 판매자의 돈이 바뀝니다.
            수수료(SALE_FEE) 조정은 시스템(플랫폼) 몫만 바꾸고 판매자가 받는 돈은 바꾸지 않습니다. 판매자에게 더 주거나 덜 주려면 판매 대금(SALE_AMOUNT)으로 넣으세요.
            판매 금액이 틀린 금액 불일치를 풀 때는 판매 대금과 수수료를 함께 맞춰야 합니다.
            예) 상품 하나의 판매 금액이 30,000이어야 하는데 40,000(대금 38,800 / 수수료 1,200)으로 들어왔고 결제는 30,000 → 대금 -9,700과 수수료 -300을 각각 넣습니다. 대금만 -10,000을 넣으면 수수료 불일치로 다시 막힙니다.
            수수료 불일치(판매 금액은 맞는데 대금·수수료 나눔이 틀림)를 맞게 고치면 두 조정이 서로 반대 부호입니다. 예) 판매 10,000인데 대금 9,000 / 수수료 1,000 → 대금 +700, 수수료 -700.
            수수료 = 판매 금액 × 3%(원 미만 버림)입니다. 판매 금액 = 판매 대금 + 수수료입니다.
            조정할 상품과 금액은 정산후보 조회(GET /api/v1/admin/payouts/candidates?orderId={orderId})로 봅니다. 아직 대기 중인 상품도 나옵니다.
            조정을 넣은 뒤에는 막힌 주문 목록(orderId 조건)을 다시 불러 pendingSaleAmountAdjustment·pendingSaleFeeAdjustment로 들어갔는지 확인하세요. 같은 조정을 사유만 바꿔 다시 넣으면 판매자가 두 번 덜 받습니다.
            대금·수수료 두 조정은 한 번에 처리되지 않습니다. 하나만 성공했으면 목록의 두 합계로 확인하고 나머지를 넣으세요.

            같은 주문 항목·종류·금액·사유의 조정이 이미 있으면 409입니다(같은 요청을 다시 보낸 경우). 일부러 한 번 더 넣으려면 사유를 다르게 적으세요.
            로그인하지 않았으면 401, 요청 형식이 틀리면 422가 먼저이고, 관리자가 아니면 403입니다.
            조정한 관리자 ID는 요청 바디가 아니라 로그인 토큰에서 받습니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "조정을 만듦. 방금 저장한 조정 정산후보"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "409", description = "같은 조정이 이미 있음 (같은 요청 재전송, 동시에 두 요청)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "같은 조정", value = """
                                    {
                                      "type": "https://api.example.com/problems/payout/duplicate-adjustment",
                                      "title": "Duplicate adjustment",
                                      "status": 409,
                                      "detail": "같은 조정이 이미 있습니다. 한 번 더 넣으려면 사유를 다르게 적으세요."
                                    }
                                    """))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (금액 0원, 사유 공백·255자 초과, 지원하지 않는 항목 종류·배송비), 원래 정산후보 없음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "요청 본문을 읽을 수 없음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutAdjustmentCreateResponse> createAdjustment(
            @Parameter(hidden = true) Long memberId,
            PayoutAdjustmentCreateRequest request
    );

    @Operation(summary = "잠기거나 멈춘 주문 해결 (다시 처리)", description = """
            관리자 전용입니다. 결과 검증 불일치·수수료 불일치로 잠긴 주문이나, 3번 실패해 멈춘 주문을 "다시 처리"로 해결합니다.
            그 주문의 해결되지 않은 실패 기록 전부에 해결 기록(관리자, 시각, 메모)을 남기고, 다음 배치에서 그 주문을 다시 검사해 정산합니다.
            조회 기간 밖으로 밀린 주문도 다시 들어갑니다. 돈을 바로 내보내지 않고 금액 검사·결과 검증을 똑같이 거칩니다.
            데이터나 코드를 고치지 않고 해결하면 다시 검사에서 또 실패해 다시 잠기거나 3번 뒤 다시 멈춥니다. 되돌리기는 없습니다.
            금액 불일치(AMOUNT_MISMATCH)·수수료 불일치(FEE_MISMATCH)로 막힌 주문은 먼저 조정을 넣고, 막힌 주문 목록(orderId 조건)의 pendingSaleAmountAdjustment·pendingSaleFeeAdjustment로 들어갔는지 확인한 뒤 해결하세요.
            해결한 뒤에 넣은 조정은 금액 검사에 들어가지 않고 지급에만 들어갑니다(다시 해결해도 같음).
            해결한 관리자 ID는 요청 바디가 아니라 로그인 토큰에서 받습니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "해결함. 방금 저장한 해결 기록"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "409", description = "해결할 잠금이나 멈춤이 없음 (이미 해결됨, 같은 주문을 동시에 두 번 요청, 잘못된 주문 ID)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "해결할 것 없음", value = """
                                    {
                                      "type": "https://api.example.com/problems/payout/nothing-to-resolve",
                                      "title": "Nothing to resolve",
                                      "status": 409,
                                      "detail": "해결할 잠금이나 멈춤이 없는 주문입니다.",
                                      "instance": "/api/v1/admin/payouts/orders/100/resolution"
                                    }
                                    """))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (메모 공백·255자 초과)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "요청 본문을 읽을 수 없음",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutOrderResolutionResponse> resolveOrder(
            @Parameter(hidden = true) Long memberId,
            @Parameter(description = "해결할 주문 ID", example = "100") Long orderId,
            PayoutOrderResolveRequest request
    );

    @Operation(summary = "해결 기록 조회 (1줄씩)", description = """
            관리자 전용입니다. 주문 하나의 해결 기록을 해결 시각 최신순으로 조회합니다(같은 시각이면 해결 기록 ID 역순).
            해결은 주문 단위로 요청하지만 기록은 해결한 실패 기록마다 1줄이라, 해결 1번에 여러 줄이 같은 요청 번호·관리자·메모로 나옵니다.
            줄마다 그 실패 기록의 사유·범주·상세·실패 시각과, 실패한 정산후보의 종류·금액을 함께 줍니다.
            해결 요청 1번을 1줄로 보려면 GET /api/v1/admin/payouts/orders/{orderId}/resolution-requests를 씁니다.
            해결 기록이 없는 주문은 빈 목록입니다(정산은 주문 정보를 갖고 있지 않아 없는 주문인지 구별하지 않습니다).
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
                    responseCode = "422", description = "요청 값 검증 실패 (page·size 범위)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "주문 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutResolutionResponse>> getResolutions(
            @Parameter(description = "주문 ID", example = "100") Long orderId,
            @ParameterObject PayoutResolutionSearchRequest request
    );

    @Operation(summary = "해결 기록 조회 (해결 요청 묶음)", description = """
            관리자 전용입니다. 주문 하나의 해결 기록을 해결 요청 1번 = 1줄로 묶어 해결 시각 최신순으로 조회합니다.
            줄마다 해결한 관리자·메모·해결 시각과, 그 요청으로 해결한 실패 기록 수를 줍니다.
            어느 실패 기록을 풀었는지는 GET /api/v1/admin/payouts/orders/{orderId}/resolutions에서 같은 요청 번호의 줄로 봅니다.
            해결 시각은 그 요청에서 가장 이른 해결 기록의 시각입니다(해결 기록마다 시각이 따로 찍혀 아주 조금씩 다를 수 있습니다).
            해결 기록이 없는 주문은 빈 목록입니다.
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
                    responseCode = "422", description = "요청 값 검증 실패 (page·size 범위)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "주문 ID가 숫자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutResolutionRequestResponse>> getResolutionRequests(
            @Parameter(description = "주문 ID", example = "100") Long orderId,
            @ParameterObject PayoutResolutionSearchRequest request
    );

    @Operation(summary = "해결 기록 전체 목록 (해결 요청 묶음)", description = """
            관리자 전용입니다. 기간 안에 관리자가 "다시 처리"로 푼 기록을 주문과 상관없이 해결 요청 1번 = 1줄로, 해결 시각 최신순으로 조회합니다(같은 시각이면 나중에 저장한 요청이 먼저).
            막힌 주문 목록의 해결됨(RESOLVED) 줄은 다음 정산 실행이 끝나면 빠지므로, 지난 해결을 돌아볼 때 이 목록을 씁니다.
            기간(from, to)은 필수이고 최대 1년입니다. 해결 시각 기준이며 from은 포함, to는 포함하지 않습니다. adminId를 주면 그 관리자가 푼 것만 봅니다.
            해결 시각은 그 요청에서 가장 이른 해결 기록의 시각이고, 기간도 이 시각으로 판정합니다(한 요청이 두 기간으로 갈라지지 않음).
            줄 모양은 주문별 묶음 보기(GET /api/v1/admin/payouts/orders/{orderId}/resolution-requests)와 같습니다. 어느 실패 기록을 풀었는지는 GET /api/v1/admin/payouts/orders/{orderId}/resolutions에서 같은 요청 번호의 줄로 봅니다.
            해결 방법(method)은 지금 REPROCESS(다시 처리)뿐입니다. 관리자 이름과 막혔던 이유는 이 목록에 없습니다(관리자 이름은 회원 모듈, 이유는 주문별 해결 기록에서 봄).
            메모는 관리자가 입력한 글이라 화면에 출력할 때 HTML 이스케이프가 필요합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공 (기간 안에 푼 기록이 없으면 빈 목록)"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (기간 없음, 시작 시각이 종료 시각보다 같거나 늦음, 1년 초과, adminId·page·size 범위)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "시각·숫자 형식이 틀림",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutResolutionRequestResponse>> getResolutionRequestsInPeriod(
            @ParameterObject PayoutResolutionRequestSearchRequest request
    );

    @Operation(summary = "막힌 주문 목록 (잠김·멈춤·해결됨)", description = """
            관리자 전용입니다. 정산이 막혀 사람이 봐야 하는 주문을 마지막 실패 시각 최신순으로 조회합니다(같은 시각이면 주문 ID 역순).
            - LOCKED 잠김: 수수료 불일치·결과 검증 불일치. 다시 검사해도 풀리지 않아 관리자가 해결해야 합니다(실패 횟수와 상관없음).
            - STOPPED 멈춤: 잠기지 않았지만 성공한 정산 실행에서 3번 실패해 자동 재검사가 멈췄습니다.
            - RESOLVED 해결됨: 관리자가 해결했고, 다음 정산 실행이 끝나면 목록에서 빠집니다(다시 막혔으면 LOCKED·STOPPED로 바뀝니다).
            잠김(LOCKED)·멈춤(STOPPED) 줄은 해결 API(POST /api/v1/admin/payouts/orders/{orderId}/resolution)가 해결할 것이 있다고 보는 주문과 같습니다. 해결됨(RESOLVED) 줄에 해결을 보내면 409입니다.
            상태(status)는 정산 실행이 끝날 때 바뀌고, 해결하면 그 줄은 바로 RESOLVED가 됩니다. 근거(evidenceNow)와 조정 합계는 부를 때마다 계산합니다.
            원인 상세는 내부 검사 내용이라 관리자 화면에만 보여 주고, 화면에 출력할 때 HTML 이스케이프가 필요합니다.

            orderId를 주면 그 주문 줄만 나옵니다(막힌 주문이 아니면 빈 목록).
            status로 상태를 고를 수 있습니다(LOCKED·STOPPED·RESOLVED 대문자, 여러 개 가능, 예: status=LOCKED&status=STOPPED). 안 주면 셋 다 나옵니다. 두 조건을 함께 주면 둘 다 맞는 줄만 나옵니다.
            기간 조건은 없습니다. 오래 막혀 있는 주문도 바로 찾아야 해서입니다.
            예전에 푼 주문은 다음 정산 실행이 끝나면 이 목록에서 빠집니다. 기간으로 훑어보려면 해결 기록 전체 목록(GET /api/v1/admin/payouts/resolution-requests), 주문 하나의 기록은 GET /api/v1/admin/payouts/orders/{orderId}/resolutions로 봅니다.

            막힌 이유(reasons)별로 할 일이 다릅니다. 이유가 여러 개면 AMOUNT_MISMATCH·FEE_MISMATCH가 하나라도 있을 때 조정이 필요할 수 있습니다.
            - AMOUNT_MISMATCH 금액 불일치(초과 청구): 금액이 틀린 상품을 찾아 판매 대금(SALE_AMOUNT)과 수수료(SALE_FEE) 조정을 함께 넣은 뒤 해결합니다. 대금만 넣으면 수수료 불일치로 다시 잠깁니다.
            - FEE_MISMATCH 수수료 불일치: 시스템 문제일 수 있어 먼저 원인을 확인합니다. 후보 금액이 틀렸으면 대금과 수수료가 계산식에 맞도록 조정을 넣은 뒤 해결합니다(원인 상세 detail에 기대값·실제값이 있습니다).
            - DEDUCTION_MISSING 결제 기록 없음: 조정하지 않습니다. 지갑 결제 기록이 들어왔는지 확인한 뒤 해결합니다.
            - VERIFY_MISMATCH 결과 검증 불일치: 조정하지 않습니다. 정산 코드 문제라 개발자가 고친 뒤 해결합니다.
            - UNEXPECTED_ERROR 예상하지 못한 오류: 조정하지 않습니다. 원인(코드 버그, 일시적인 DB 오류 등)을 확인한 뒤 해결합니다.
            - ORDER_HELD 함께 보류: 같은 주문의 다른 상품 때문에 함께 보류된 기록입니다(수수료 불일치 때 생김). 원인 기록이 이미 빠진 주문에만 나옵니다. 정산후보 조회로 그 주문을 확인한 뒤 해결합니다.

            pendingSaleAmountAdjustment·pendingSaleFeeAdjustment는 "지금 해결하면 금액 검사에 들어갈 조정 합계"를 판매 대금·수수료별로 나눈 값입니다(원).
            같은 주문의 해결 안 된 금액 불일치·수수료 불일치 실패 뒤에 넣은 조정만 더하고, 없으면 0입니다.
            수수료 불일치를 맞게 고치면 두 값이 서로 반대 부호로 나옵니다(예: 대금 +700, 수수료 −700).
            두 값이 0인데 조정을 넣었다면: 해결됨(RESOLVED) 줄이거나, 막힌 이유가 금액·수수료 불일치가 아니거나, 실패 전에 미리 넣었거나, 다른 주문에 넣었거나, 같은 종류 조정끼리 서로 상쇄돼 합이 0인 것입니다. 미리 넣은 조정은 조정 API 설명대로 상쇄합니다.
            두 값은 주문 단위 합계라, 판매자가 여럿인 주문에서 어느 판매자 상품에 넣었는지는 알려 주지 않습니다. 정산후보 조회(orderId)의 sellerId로 확인하세요.
            근거(evidenceNow)의 후보 합계에는 해결 전 조정이 들어가지 않습니다. 정산 실행이 도는 중(매일 11:00 정기 실행, 관리자 수동 실행)에는 다음 실행이 끝날 때까지 값이 잠시 어긋날 수 있습니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공 (막힌 주문이 없으면 빈 목록)"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (status 값이 LOCKED·STOPPED·RESOLVED가 아님(PENDING·소문자 포함), orderId·page·size 범위)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "400", description = "시각·숫자 형식이 틀림",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutBlockedOrderResponse>> getBlockedOrders(
            @ParameterObject PayoutBlockedOrderSearchRequest request
    );

    @Operation(summary = "대사 관리자 확인 목록", description = """
            관리자 전용입니다. 정산·지갑 대사(매일 지갑에 정산 입금을 묻는 확인)에서 관리자 확인 필요가 된 정산을 이유와 함께 조회합니다.
            확인 대기·일치인 정산은 나오지 않습니다. 정렬은 새 정산 먼저(대사 줄이 생긴 순서의 역순)입니다.
            - NOT_FOUND: 3번 물을 때까지 입금이 확인되지 않았고, 그중 한 번 이상 지갑이 "없음"으로 답함(나머지는 답이 없었을 수 있음. notFoundCount로 확인)
            - NO_RESPONSE: 3번 물을 때까지 지갑이 한 번도 답하지 않음(지갑 수신 장애일 수 있음)
            - PAYEE_MISMATCH: 입금은 있는데 판매자가 다름(지갑이 답한 판매자·금액을 함께 보여 줍니다)
            - AMOUNT_MISMATCH: 같은 판매자에게 입금은 있는데 금액이 다름(지갑이 답한 판매자·금액을 함께 보여 줍니다)
            대사는 어긋난 건을 자동으로 고치지 않습니다. 이 목록은 조회만 하고, 다시 보내기·완료 표시 같은 처리는 없습니다.
            NOT_FOUND·NO_RESPONSE로 닫힌 정산에 지갑 답이 늦게 와서 일치로 바뀌면 목록에서 빠집니다.
            PAYEE_MISMATCH·AMOUNT_MISMATCH로 닫힌 정산은 늦은 답으로 바뀌지 않습니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공 (관리자 확인 필요인 정산이 없으면 빈 목록)"),
            @ApiResponse(
                    responseCode = "401", description = "로그인 필요 (토큰 없음·만료·위조)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "403", description = "관리자가 아님",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "422", description = "요청 값 검증 실패 (page·size 범위)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
            )
    })
    ResponseEntity<PayoutPageResponse<PayoutDepositCheckReviewResponse>> getDepositCheckReviews(
            @ParameterObject PayoutDepositCheckReviewSearchRequest request
    );
}
