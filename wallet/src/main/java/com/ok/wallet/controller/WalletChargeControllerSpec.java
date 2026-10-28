package com.ok.wallet.controller;

import com.ok.wallet.controller.dto.ChargeConfirmRequest;
import com.ok.wallet.controller.dto.ChargeFailRequest;
import com.ok.wallet.controller.dto.ChargeRequest;
import com.ok.wallet.controller.dto.ChargeResponse;
import com.ok.wallet.controller.dto.ChargeStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Wallet Charge", description = "예치금 충전 API (토스페이먼츠)")
interface WalletChargeControllerSpec {
    @Operation(summary = "충전 요청",
            description = "충전 건을 대기 상태로 만들고, 토스 결제창 호출에 쓸 충전 번호를 반환한다. "
                    + "현재 버전 필수 약관에 동의해야 하며, 충전 후 잔액은 500,000원을 넘을 수 없다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "충전 건 생성"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "409", description = "필수 약관 미동의, 잔액 보유 한도 초과"),
            @ApiResponse(responseCode = "422", description = "충전 금액 규칙 위반, 요청 값 검증 실패")
    })
    ResponseEntity<ChargeResponse> request(Long memberId, ChargeRequest request);

    @Operation(summary = "충전 승인",
            description = "토스 successUrl로 받은 값으로 결제를 승인한다. 결과는 status로 확인한다. "
                    + "COMPLETED는 잔액 반영 완료, FAILED는 결제 거절, IN_PROGRESS는 승인 결과 확인 중이다. "
                    + "같은 충전 건으로 다시 요청해도 승인은 한 번만 일어나고 현재 상태를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 처리 결과 (status 확인)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "404", description = "충전 건이 없거나 본인의 충전 건이 아님"),
            @ApiResponse(responseCode = "409", description = "다른 충전이 먼저 반영되어 잔액 보유 한도 초과"),
            @ApiResponse(responseCode = "422", description = "결제 금액이 충전 요청 금액과 다름, 요청 값 검증 실패")
    })
    ResponseEntity<ChargeStatusResponse> confirm(Long memberId, ChargeConfirmRequest request);

    @Operation(summary = "결제 실패 처리",
            description = "토스 failUrl로 받은 값으로 결제 전 충전 건을 실패로 바꾼다. "
                    + "이미 승인이 시작됐거나 끝난 충전 건은 바꾸지 않고 현재 상태를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "실패 처리 결과 (status 확인)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "404", description = "충전 건이 없거나 본인의 충전 건이 아님"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패")
    })
    ResponseEntity<ChargeStatusResponse> fail(Long memberId, ChargeFailRequest request);

    @Operation(summary = "충전 건 상태 조회",
            description = "본인 충전 건의 현재 상태를 조회한다. 승인 응답이 IN_PROGRESS(확인 중)였을 때 "
                    + "잠시 뒤 다시 조회해 COMPLETED·FAILED로 바뀌었는지 확인하는 데 쓴다. 상태를 바꾸지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현재 상태 (status 확인)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "404", description = "충전 건이 없거나 본인의 충전 건이 아님")
    })
    ResponseEntity<ChargeStatusResponse> getStatus(
            Long memberId, @Parameter(description = "충전 번호", example = "CHG_3f2b1c9e-8a4d-4e6f-9b1a-2c3d4e5f6a7b") String chargeNo);
}
