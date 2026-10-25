package com.ok.wallet.controller;

import com.ok.wallet.controller.dto.BuyerBalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Wallet Balance", description = "예치금 잔액 조회 API")
interface WalletBalanceControllerSpec {
    @Operation(summary = "본인 잔액 조회",
            description = "로그인한 회원의 예치금 잔액과 잔액 보유 한도를 조회한다. 지갑이 아직 없으면 잔액은 0이다. "
                    + "충전 가능 금액은 maxBalance - balance로 계산한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "잔액 조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요")
    })
    ResponseEntity<BuyerBalanceResponse> getBalance(Long memberId);
}
