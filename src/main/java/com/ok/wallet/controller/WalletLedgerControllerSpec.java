package com.ok.wallet.controller;

import com.ok.wallet.controller.dto.LedgerHistoryPageResponse;
import com.ok.wallet.controller.dto.LedgerHistoryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;

@Tag(name = "Wallet Ledger", description = "예치금 이용 내역 API")
interface WalletLedgerControllerSpec {
    @Operation(summary = "본인 이용 내역 조회",
            description = "로그인한 회원의 예치금 이용 내역을 최신순으로 페이지 단위로 조회한다. "
                    + "기간(시작일·종료일 모두 포함)과 유형(CHARGE·PAYMENT)으로 거를 수 있고, 지갑이 없으면 빈 목록이다. "
                    + "결제 내역에는 주문 상세로 이동할 수 있도록 주문 ID가 함께 온다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "422", description = "시작일이 종료일보다 늦음, 알 수 없는 유형, 페이지 값 검증 실패")
    })
    ResponseEntity<LedgerHistoryPageResponse> getHistory(Long memberId, @ParameterObject LedgerHistoryRequest request);
}
