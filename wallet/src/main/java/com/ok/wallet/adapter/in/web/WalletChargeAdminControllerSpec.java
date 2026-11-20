package com.ok.wallet.adapter.in.web;

import com.ok.wallet.adapter.in.web.dto.ChargeAdminDetailResponse;
import com.ok.wallet.adapter.in.web.dto.ChargeAdminReasonRequest;
import com.ok.wallet.adapter.in.web.dto.ChargeAdminResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Wallet Charge Admin", description = "복구가 끝내지 못한 충전 건 관리자 처리 API")
interface WalletChargeAdminControllerSpec {
    String CHARGE_NO_EXAMPLE = "CHG_3f2b1c9e-8a4d-4e6f-9b1a-2c3d4e5f6a7b";

    @Operation(summary = "처리할 충전 건 목록",
            description = "복구가 토스 답만으로 정하지 못해 멈춘 처리 중 건(heldForReview)과, 실패로 처리된 뒤 토스가 승인해 "
                    + "아직 결제를 취소하지 않은 건(approvedAfterFailureAt)을 요청 시각 순으로 최대 100개 돌려준다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리할 충전 건 목록"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님")
    })
    ResponseEntity<List<ChargeAdminResponse>> findTargets();

    @Operation(summary = "충전 건과 토스 결제 상태 조회",
            description = "처리를 고르기 전에 충전 건과 토스 결제 상태를 함께 본다. 토스에 충전 번호로 조회한다. 상태를 바꾸지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "충전 건과 토스 결제 상태 (tossLookup이 UNKNOWN이면 토스 답을 못 받음)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님"),
            @ApiResponse(responseCode = "404", description = "충전 건이 없음")
    })
    ResponseEntity<ChargeAdminDetailResponse> findDetail(
            @Parameter(description = "충전 번호", example = CHARGE_NO_EXAMPLE) String chargeNo);

    @Operation(summary = "잔액 반영",
            description = "멈춘 처리 중 건을 잔액에 반영하고 완료한다. 처리 직전에 토스에 다시 물어, "
                    + "토스가 같은 금액으로 승인한(DONE) 결제만 반영한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "완료됨"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님"),
            @ApiResponse(responseCode = "404", description = "충전 건이 없음"),
            @ApiResponse(responseCode = "409", description = "멈춘 처리 중 건이 아님, 결제 취소를 요청한 건, 토스가 같은 금액으로 승인하지 않음"),
            @ApiResponse(responseCode = "503", description = "토스 결제 상태를 확인하지 못함 (잠시 후 다시 시도)")
    })
    ResponseEntity<ChargeAdminResponse> complete(
            Long adminId, @Parameter(description = "충전 번호", example = CHARGE_NO_EXAMPLE) String chargeNo);

    @Operation(summary = "실패 처리",
            description = "멈춘 처리 중 건을 실패로 바꾼다. 처리 직전에 토스에 다시 물어, 결제가 없거나 "
                    + "승인 실패·만료·취소된 결제만 실패로 둔다. 승인됐거나 아직 진행 중이면 거부한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "실패로 바뀜"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님"),
            @ApiResponse(responseCode = "404", description = "충전 건이 없음"),
            @ApiResponse(responseCode = "409", description = "멈춘 처리 중 건이 아님, 결제 취소를 요청한 건, 토스 결제가 승인됐거나 진행 중"),
            @ApiResponse(responseCode = "422", description = "사유 없음, 200자 초과"),
            @ApiResponse(responseCode = "503", description = "토스 결제 상태를 확인하지 못함 (잠시 후 다시 시도)")
    })
    ResponseEntity<ChargeAdminResponse> fail(
            Long adminId,
            @Parameter(description = "충전 번호", example = CHARGE_NO_EXAMPLE) String chargeNo,
            ChargeAdminReasonRequest request);

    @Operation(summary = "결제 취소 (환불)",
            description = "토스 결제를 전액 취소하고 충전 건에 취소 시각을 남긴다. 멈춘 처리 중 건은 실패가 되고, "
                    + "승인 뒤 실패한 건은 원래 실패 사유를 그대로 둔다. 토스가 같은 금액으로 승인한(DONE) 결제만 취소하고, "
                    + "이미 토스에서 취소된 결제는 다시 취소하지 않고 기록만 한다. 결과를 알 수 없으면 기록하지 않고 503으로 답하며, "
                    + "다시 시도해도 두 번 취소되지 않는다. 멈춘 처리 중 건은 토스를 부르기 전에 취소 요청 시각을 남겨, "
                    + "취소 결과가 정해질 때까지 반영·실패를 막는다(토스가 거절해도 표시는 남고, 사람이 토스를 확인한 뒤 지운다).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "결제 취소됨"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님"),
            @ApiResponse(responseCode = "404", description = "충전 건이 없음"),
            @ApiResponse(responseCode = "409", description = "취소할 건이 아님, 토스 결제가 전액 승인 상태가 아님, 토스가 취소를 거절함"),
            @ApiResponse(responseCode = "422", description = "사유 없음, 200자 초과"),
            @ApiResponse(responseCode = "503", description = "토스 결제 상태나 취소 결과를 확인하지 못함 (잠시 후 다시 시도)")
    })
    ResponseEntity<ChargeAdminResponse> cancelPayment(
            Long adminId,
            @Parameter(description = "충전 번호", example = CHARGE_NO_EXAMPLE) String chargeNo,
            ChargeAdminReasonRequest request);
}
