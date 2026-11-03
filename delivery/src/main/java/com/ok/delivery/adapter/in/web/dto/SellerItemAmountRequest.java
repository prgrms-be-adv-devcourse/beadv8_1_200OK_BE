package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.SellerItemAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "판매자 한 명의 상품 금액 합계")
public record SellerItemAmountRequest(

        @Schema(description = "판매자 회원 ID", example = "10")
        @NotNull(message = "판매자 ID는 필수입니다.")
        @Positive(message = "판매자 ID는 1 이상이어야 합니다.")
        Long sellerId,

        @Schema(description = "이 판매자 상품의 금액 합계 (원). 무료배송 기준과 비교한다", example = "45000")
        @NotNull(message = "상품 금액은 필수입니다.")
        @PositiveOrZero(message = "상품 금액은 0 이상이어야 합니다.")
        Long itemAmount
) {

    public SellerItemAmount toSellerItemAmount() {
        return new SellerItemAmount(sellerId, itemAmount);
    }
}
