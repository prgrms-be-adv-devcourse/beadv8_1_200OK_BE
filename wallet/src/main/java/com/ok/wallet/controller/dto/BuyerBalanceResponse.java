package com.ok.wallet.controller.dto;

import com.ok.wallet.service.dto.BuyerBalanceResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "구매자 잔액 조회 응답")
public record BuyerBalanceResponse(
        @Schema(description = "현재 예치금 잔액 (원). 지갑이 없으면 0", example = "120000")
        long balance,

        @Schema(description = "잔액 보유 한도 (원). 충전 가능 금액은 maxBalance - balance", example = "500000")
        long maxBalance
) {
    public static BuyerBalanceResponse from(BuyerBalanceResult result) {
        return new BuyerBalanceResponse(result.balance(), result.maxBalance());
    }
}
