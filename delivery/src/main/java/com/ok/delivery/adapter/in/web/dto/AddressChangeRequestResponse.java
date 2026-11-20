package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.AddressChangeRequestResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "배송지 변경 요청")
public record AddressChangeRequestResponse(

        @Schema(description = "배송지 변경 요청 ID", example = "1")
        Long requestId,

        @Schema(description = "shipment ID", example = "1")
        Long shipmentId,

        @Schema(description = "요청 상태 (PENDING, APPROVED, REJECTED)", example = "PENDING")
        String status,

        @Schema(description = "변경으로 늘어나는 추가 운임 안내 금액(원). 결제하지 않고 판매자가 직접 받는다", example = "3000")
        int additionalFeeNotice,

        @Schema(description = "거절 사유. 거절되지 않았으면 null", example = "상품 준비가 완료되어 배송지 변경이 불가능합니다.")
        String rejectReason,

        @Schema(description = "요청 시각", example = "2026-10-20T14:30:00")
        LocalDateTime requestedAt,

        @Schema(description = "승인·거절 시각. 처리 전이면 null", example = "2026-10-20T15:00:00")
        LocalDateTime processedAt
) {

    public static AddressChangeRequestResponse from(AddressChangeRequestResult result) {
        return new AddressChangeRequestResponse(
                result.requestId(),
                result.shipmentId(),
                result.status(),
                result.additionalFeeNotice(),
                result.rejectReason(),
                result.requestedAt(),
                result.processedAt());
    }
}
