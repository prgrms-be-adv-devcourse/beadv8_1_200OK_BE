package com.ok.wallet.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 지갑 잔액 조회 응답")
public record SellerBalanceResponse(
        @Schema(description = "판매자 지갑에 입금된 정산금 잔액 (원). 지갑이 없으면 0", example = "127000")
        long balance
) {
}
