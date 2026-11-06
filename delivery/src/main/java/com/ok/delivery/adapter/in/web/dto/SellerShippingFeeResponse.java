package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.SellerShippingFeeResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 한 명의 배송비와 계산에 쓴 판매자 설정 값")
public record SellerShippingFeeResponse(

        @Schema(description = "판매자 회원 ID", example = "10")
        Long sellerId,

        @Schema(description = "판매자 설정의 기본 배송비 (원). 무료배송이어도 설정 값이다", example = "3000")
        long baseShippingFee,

        @Schema(description = "판매자 설정의 무료배송 기준 금액 (원). null이면 무료배송이 없다", example = "50000", nullable = true)
        Long freeShippingThreshold,

        @Schema(description = "무료배송을 적용한 뒤의 기본 배송비 (원). 추가 운임은 포함하지 않는다", example = "0")
        long shippingFee,

        @Schema(description = "이 판매자 송장에 붙는 도서산간 추가 운임 (원). 일반 지역이면 0", example = "3000")
        long regionSurcharge
) {

    public static SellerShippingFeeResponse from(SellerShippingFeeResult result) {
        return new SellerShippingFeeResponse(
                result.sellerId(),
                result.baseShippingFee(),
                result.freeShippingThreshold() == null ? null : result.freeShippingThreshold().longValue(),
                result.shippingFee(),
                result.regionSurcharge());
    }
}
