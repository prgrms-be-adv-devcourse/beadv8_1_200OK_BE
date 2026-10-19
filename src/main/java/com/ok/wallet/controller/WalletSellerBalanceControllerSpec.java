package com.ok.wallet.controller;

import com.ok.wallet.controller.dto.SellerBalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Wallet Seller", description = "판매자 지갑 API")
interface WalletSellerBalanceControllerSpec {
    @Operation(summary = "판매자 지갑 잔액 조회",
            description = "로그인한 판매자의 판매자 지갑 잔액(입금된 정산금)을 조회한다. "
                    + "판매자 지갑은 판매자 승인 후 만들어지며, 아직 없으면 잔액은 0이다. "
                    + "정산 대기·완료 내역은 정산 API에서 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "잔액 조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "판매자가 아님")
    })
    ResponseEntity<SellerBalanceResponse> getBalance(Long memberId);
}
