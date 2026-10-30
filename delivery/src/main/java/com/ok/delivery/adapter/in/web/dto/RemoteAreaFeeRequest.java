package com.ok.delivery.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 도서산간 추가 운임 조회 조건이다.
 * 파라미터 이름 {@code zipCode}는 주문 모듈의 {@code DeliveryApiClient}가 보내는 이름에 맞춘다.
 */
@Schema(description = "도서산간 추가 운임 조회 조건")
public record RemoteAreaFeeRequest(

        @Schema(description = "우편번호 (숫자 5자리)", example = "63100")
        @NotBlank(message = "우편번호는 필수입니다.")
        @Pattern(regexp = "^\\d{5}$", message = "우편번호는 숫자 5자리여야 합니다.")
        String zipCode
) {
}
