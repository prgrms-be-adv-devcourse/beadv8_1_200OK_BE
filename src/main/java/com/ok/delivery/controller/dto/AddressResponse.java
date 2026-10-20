package com.ok.delivery.controller.dto;

import com.ok.delivery.service.dto.AddressResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "배송지")
public record AddressResponse(

        @Schema(description = "수령인 이름", example = "홍길동")
        String recipientName,

        @Schema(description = "연락처 (숫자만)", example = "01012345678")
        String recipientPhone,

        @Schema(description = "우편번호", example = "06236")
        String postalCode,

        @Schema(description = "주소", example = "서울시 강남구 테헤란로 1")
        String address,

        @Schema(description = "상세 주소", example = "101동 202호")
        String addressDetail,

        @Schema(description = "배송 요청사항. 없으면 null", example = "문 앞에 놓아주세요")
        String deliveryNote
) {

    public static AddressResponse from(AddressResult result) {
        return new AddressResponse(
                result.recipientName(),
                result.recipientPhone(),
                result.postalCode(),
                result.address(),
                result.addressDetail(),
                result.deliveryNote());
    }
}
