package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.ShipmentItemResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "배송 상품 항목 (상품명, 옵션명, 단가는 결제 시점 값)")
public record ShipmentItemResponse(

        @Schema(description = "주문 상품 ID", example = "1")
        Long orderItemId,

        @Schema(description = "상품명", example = "무선 키보드")
        String productName,

        @Schema(description = "옵션명. 없으면 null", example = "블랙")
        String optionName,

        @Schema(description = "주문 수량", example = "2")
        int quantity,

        @Schema(description = "단가(원)", example = "30000")
        int unitPrice,

        @Schema(description = "취소 확정된 수량", example = "0")
        int canceledQuantity,

        @Schema(description = "구매확정 여부", example = "false")
        boolean confirmed
) {

    public static ShipmentItemResponse from(ShipmentItemResult result) {
        return new ShipmentItemResponse(
                result.orderItemId(),
                result.productName(),
                result.optionName(),
                result.quantity(),
                result.unitPrice(),
                result.canceledQuantity(),
                result.confirmed());
    }
}
