package com.ok.delivery.controller.dto;

import com.ok.delivery.service.dto.RegionResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 우편번호의 도서산간 여부와 송장 한 건에 붙는 추가 운임이다.
 * 필드 이름과 타입은 주문 모듈의 {@code RemoteAreaFeeResponse}와 맞춘다. 바꾸면 주문의 역직렬화가 깨진다.
 */
@Schema(description = "도서산간 추가 운임")
public record RemoteAreaFeeResponse(

        @Schema(description = "조회한 우편번호 (숫자 5자리)", example = "63100")
        String zipCode,

        @Schema(description = "도서산간 지역 여부. 추가 운임이 0원인 산간 지역도 true다", example = "true")
        boolean remoteArea,

        @Schema(description = "송장(판매자) 한 건에 붙는 추가 운임(원). 일반 지역이면 0", example = "3000")
        long additionalFee
) {

    public static RemoteAreaFeeResponse of(String zipCode, RegionResult result) {
        return new RemoteAreaFeeResponse(zipCode, result.isRemoteArea(), result.surcharge());
    }
}
