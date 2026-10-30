package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.ShippingFeeQuoteResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "배송비 견적")
public record ShippingFeeQuoteResponse(

        @Schema(description = "배송지의 지역 그룹 코드. 도서산간이 아니면 GENERAL", example = "JEJU_MAIN")
        String regionGroup,

        @Schema(description = "판매자별 견적 (요청한 판매자 순서)")
        List<SellerShippingQuoteResponse> sellers,

        @Schema(description = "모든 판매자의 배송비 합계 (원)", example = "9000")
        int totalShippingFee
) {

    public static ShippingFeeQuoteResponse from(ShippingFeeQuoteResult result) {
        return new ShippingFeeQuoteResponse(
                result.regionGroup(),
                result.sellers().stream().map(SellerShippingQuoteResponse::from).toList(),
                result.totalShippingFee());
    }
}
