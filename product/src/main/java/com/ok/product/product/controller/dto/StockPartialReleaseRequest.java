package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.StockPartialReleaseCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "재고 일부 해제 요청")
public record StockPartialReleaseRequest(

	@Schema(description = "환불 ID. 같은 환불 ID로 다시 보내면 내용이 같을 때만 성공으로 응답한다", example = "7")
	@NotNull(message = "환불 ID는 필수입니다.")
	Long refundId,

	@Schema(description = "해제할 옵션 ID", example = "55")
	@NotNull(message = "옵션 ID는 필수입니다.")
	Long productOptionId,

	@Schema(description = "해제할 수량 (1 이상, 해제 가능 수량 이하)", example = "1")
	@NotNull(message = "수량은 필수입니다.")
	@Positive(message = "수량은 1 이상이어야 합니다.")
	Integer quantity
) {

	public StockPartialReleaseCommand toCommand(Long orderId) {
		return new StockPartialReleaseCommand(orderId, refundId, productOptionId, quantity);
	}
}
