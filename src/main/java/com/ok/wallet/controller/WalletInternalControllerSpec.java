package com.ok.wallet.controller;

import com.ok.wallet.controller.dto.InternalBuyerBalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Wallet Internal", description = "모듈 간 내부 API (같은 컨테이너에서 온 요청만 받음)")
interface WalletInternalControllerSpec {
    @Operation(summary = "구매자 잔액 조회 (주문 서버용)",
            description = "주문 저장·재고 선점 전에 잔액 부족을 미리 거르기 위한 조회다. 지갑이 없으면 잔액은 0이다. "
                    + "조회 후 차감 사이에 잔액이 바뀔 수 있어, 최종 결과는 결제 완료·실패 이벤트로 확인한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "잔액 조회 성공"),
            @ApiResponse(responseCode = "401", description = "같은 컨테이너(loopback)가 아닌 곳에서 토큰 없이 호출"),
            @ApiResponse(responseCode = "403", description = "같은 컨테이너(loopback)가 아닌 곳에서 토큰을 가지고 호출")
    })
    ResponseEntity<InternalBuyerBalanceResponse> getBuyerBalance(
            @Parameter(description = "구매자 회원 ID", example = "1") Long memberId);
}
