package com.ok.delivery.controller.dto;

import com.ok.delivery.service.dto.ShipmentDispatchCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "출고 및 송장 등록 요청")
public record ShipmentDispatchRequest(

        // 컨트롤러 계층은 도메인 enum(Carrier)을 쓸 수 없어 허용 값을 문자열로 검사한다. 택배사를 추가하면 여기도 함께 바꾼다
        @Schema(description = "택배사 (CJ_LOGISTICS, POST_OFFICE, HANJIN, LOTTE, LOGEN)", example = "CJ_LOGISTICS")
        @NotBlank(message = "택배사는 필수입니다.")
        @Pattern(regexp = "CJ_LOGISTICS|POST_OFFICE|HANJIN|LOTTE|LOGEN",
                message = "택배사는 CJ_LOGISTICS, POST_OFFICE, HANJIN, LOTTE, LOGEN 중 하나여야 합니다.")
        String carrier,

        // 형식(숫자 10~13자리)은 규칙을 한 곳에서 바꿀 수 있게 Shipment가 검사한다
        @Schema(description = "송장번호 (숫자 10~13자리, 같은 택배사의 다른 shipment와 중복 불가)", example = "1234567890")
        @NotBlank(message = "송장번호는 필수입니다.")
        String trackingNumber
) {

    public ShipmentDispatchCommand toCommand(Long shipmentId, Long sellerId) {
        return new ShipmentDispatchCommand(shipmentId, sellerId, carrier, trackingNumber);
    }
}
