package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.CarrierNotificationCommand;
import io.swagger.v3.oas.annotations.media.Schema;

// 택배사가 보내는 본문이 고정이라 값 검증을 두지 않는다. 비어 있거나 모르는 값은 유스케이스가 무시해 2XX로 답한다
@Schema(description = "택배사의 배송 상태 변경 알림")
public record CarrierNotificationRequest(

        @Schema(description = "택배사가 쓰는 식별자", example = "kr.cjlogistics")
        String carrierId,

        @Schema(description = "송장번호", example = "1234567890123")
        String trackingNumber
) {

    public CarrierNotificationCommand toCommand() {
        return new CarrierNotificationCommand(carrierId, trackingNumber);
    }
}
