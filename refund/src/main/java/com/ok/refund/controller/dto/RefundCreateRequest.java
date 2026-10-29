package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundRequestCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "환불(출고 전 취소) 요청")
public record RefundCreateRequest(

        @Schema(description = "주문 ID", example = "10")
        @NotNull(message = "주문 ID는 필수입니다.")
        Long orderId,

        @Schema(description = "취소할 주문 상품 ID", example = "101")
        @NotNull(message = "주문 상품 ID는 필수입니다.")
        Long orderItemId,

        @Schema(description = "취소할 개수 (1 이상, 남은 개수 이하)", example = "1")
        @NotNull(message = "취소 개수는 필수입니다.")
        @Min(value = 1, message = "취소 개수는 1 이상이어야 합니다.")
        Integer quantity
) {

    // 구매자 ID는 본문이 아니라 로그인 토큰에서 받는다. 본문으로 받으면 남의 이름으로 요청할 수 있다
    public RefundRequestCommand toCommand(Long buyerId) {
        return new RefundRequestCommand(buyerId, orderId, orderItemId, quantity);
    }
}
