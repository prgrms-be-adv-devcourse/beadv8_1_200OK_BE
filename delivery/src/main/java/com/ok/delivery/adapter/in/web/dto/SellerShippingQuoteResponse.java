package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.SellerShippingQuoteResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "판매자(송장) 한 건의 배송비와 예정일 견적")
public record SellerShippingQuoteResponse(

        @Schema(description = "판매자 회원 ID", example = "10")
        Long sellerId,

        @Schema(description = "기본 배송비 (원). 무료배송 기준 이상이면 0", example = "3000")
        int baseShippingFee,

        @Schema(description = "도서산간 추가 운임 (원). 무료배송이어도 붙는다", example = "3000")
        int regionSurcharge,

        @Schema(description = "이 판매자의 배송비 (기본 배송비 + 추가 운임, 원)", example = "6000")
        int shippingFee,

        @Schema(description = "출고 예정일", example = "2026-10-16")
        LocalDate expectedShipDate,

        @Schema(description = "도착 예정일 범위의 시작", example = "2026-10-21")
        LocalDate arrivalStartDate,

        @Schema(description = "도착 예정일 범위의 끝", example = "2026-10-22")
        LocalDate arrivalEndDate
) {

    public static SellerShippingQuoteResponse from(SellerShippingQuoteResult result) {
        return new SellerShippingQuoteResponse(
                result.sellerId(),
                result.baseShippingFee(),
                result.regionSurcharge(),
                result.shippingFee(),
                result.expectedShipDate(),
                result.arrivalStartDate(),
                result.arrivalEndDate());
    }
}
