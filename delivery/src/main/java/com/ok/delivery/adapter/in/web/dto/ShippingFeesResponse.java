package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.ShippingFeesResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "주문 생성용 판매자별 배송비")
public record ShippingFeesResponse(

        @Schema(description = "판매자별 배송비 (요청한 모든 판매자를 요청 순서대로)")
        List<SellerShippingFeeResponse> sellers
) {

    public static ShippingFeesResponse from(ShippingFeesResult result) {
        return new ShippingFeesResponse(result.sellers().stream().map(SellerShippingFeeResponse::from).toList());
    }
}
