package com.ok.refund.adapter.in.web.dto;

import com.ok.refund.application.port.in.RefundRequestCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "환불(출고 전 취소) 요청")
public record RefundCreateRequest(

        @Schema(description = "주문 ID", example = "10")
        @NotNull(message = "주문 ID는 필수입니다.")
        Long orderId,

        @Schema(description = "취소할 주문 상품 ID", example = "101")
        @NotNull(message = "주문 상품 ID는 필수입니다.")
        Long orderItemId,

        @Schema(description = "취소할 개수 (1 이상, 산 개수 이하)", example = "1")
        @NotNull(message = "취소 개수는 필수입니다.")
        @Min(value = 1, message = "취소 개수는 1 이상이어야 합니다.")
        Integer quantity,

        @Schema(description = "취소 사유 (선택, 255자 이하). 판매자가 목록에서 보고 승인·거절을 판단한다", example = "옵션을 잘못 골랐습니다.")
        @Size(max = 255, message = "취소 사유는 255자 이하여야 합니다.")
        String cancelReason
) {

    // 공백만 적은 사유는 안 적은 것과 같게 본다. 목록에 빈 사유가 보이지 않게 하려고 길이 검증 전에 비운다
    public RefundCreateRequest {
        cancelReason = cancelReason == null || cancelReason.isBlank() ? null : cancelReason.strip();
    }

    // 구매자 ID는 본문이 아니라 로그인 토큰에서 받는다. 본문으로 받으면 남의 이름으로 요청할 수 있다
    public RefundRequestCommand toCommand(Long buyerId) {
        return new RefundRequestCommand(buyerId, orderId, orderItemId, quantity, cancelReason);
    }
}
