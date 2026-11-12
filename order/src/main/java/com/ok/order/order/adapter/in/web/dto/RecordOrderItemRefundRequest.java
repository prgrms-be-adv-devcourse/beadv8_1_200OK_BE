package com.ok.order.order.adapter.in.web.dto;

import com.ok.order.order.application.dto.RecordOrderItemRefundCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

// 필드 이름은 환불 RefundOrderCancellationRequest와의 약속이다(요청서 ②). 이름이 어긋나면 값이 비어 422가 된다
@Schema(description = "환불 취소 반영 요청")
public record RecordOrderItemRefundRequest(

        @Schema(description = "환불 모듈 환불 ID. 같은 ID로 다시 보내면 한 번만 반영한다", example = "7")
        @NotNull(message = "환불 ID는 필수입니다.")
        @Positive(message = "환불 ID는 1 이상이어야 합니다.")
        Long refundId,

        @Schema(description = "환불 수량", example = "1")
        @NotNull(message = "환불 수량은 필수입니다.")
        @Positive(message = "환불 수량은 1 이상이어야 합니다.")
        Integer quantity,

        @Schema(description = "환불 금액에서 뺀 배송비 (원). 빼지 않았으면 0. 구매확정 때 판매자 몫 배송비에 더한다", example = "3000")
        @NotNull(message = "뺀 배송비는 필수입니다.")
        @PositiveOrZero(message = "뺀 배송비는 0 이상이어야 합니다.")
        Long deductedShippingFee
) {
    public RecordOrderItemRefundCommand toCommand(Long orderItemId) {
        return new RecordOrderItemRefundCommand(orderItemId, refundId, quantity, deductedShippingFee);
    }
}
