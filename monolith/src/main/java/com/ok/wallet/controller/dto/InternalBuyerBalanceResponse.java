package com.ok.wallet.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 주문 모듈 WalletApiClient가 받는 응답(WalletBalanceResponse)과 필드 이름을 맞춘다. 이름을 바꾸면 주문 쪽도 함께 바꿔야 한다.
@Schema(description = "주문 서버용 구매자 잔액 조회 응답")
public record InternalBuyerBalanceResponse(
        @Schema(description = "회원 ID", example = "1")
        Long memberId,

        @Schema(description = "현재 예치금 잔액 (원). 지갑이 없으면 0", example = "120000")
        long balance
) {
}
