package com.ok.payout.controller;

import com.ok.payout.controller.dto.PayoutRunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * PayoutSkipWaitRunController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Payout (Admin)", description = "관리자 정산 API (개발용, prod 미등록)")
interface PayoutSkipWaitRunControllerSpec {

    @Operation(summary = "정산 대기 건너뛰기 실행 (임시)", description = """
            정산 대기 기간(15일)을 기다리지 않고 지금 있는 미정산 후보를 정산합니다. 발표·연동 테스트용 임시 기능입니다.
            관리자만 실행할 수 있습니다.
            정산 기록이 실제로 남습니다. 이미 정산된 후보는 다시 정산하지 않습니다.
            배치가 COMPLETED가 아니면 서버 오류(500)로 응답합니다. 실행 ID와 배치 상태는 서버 로그에 남습니다.
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
    ResponseEntity<PayoutRunResponse> runSkippingWait() throws Exception;
}
