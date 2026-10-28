package com.ok.delivery.controller.dto;

import com.ok.delivery.service.dto.PostalRegionResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "도서산간 우편번호")
public record PostalRegionResponse(

        @Schema(description = "우편번호 (숫자 5자리)", example = "63000")
        String postalCode,

        @Schema(description = "지역 그룹 코드", example = "JEJU_ISLANDS")
        String regionGroup,

        @Schema(description = "추가 소요일 (영업일)", example = "2")
        int extraDeliveryDays
) {

    public static PostalRegionResponse from(PostalRegionResult result) {
        return new PostalRegionResponse(result.postalCode(), result.regionGroup(), result.extraDeliveryDays());
    }
}
