package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.StockChange;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "재고 배정 항목")
public record StockAllocationItemRequest(

	@Schema(description = "옵션 ID", example = "11")
	@NotNull(message = "옵션 ID는 필수입니다.")
	Long optionId,

	@Schema(description = "수량 (1 이상). 같은 옵션이 여러 줄이면 합쳐서 배정한다", example = "2")
	@NotNull(message = "수량은 필수입니다.")
	@Positive(message = "수량은 1 이상이어야 합니다.")
	Integer quantity
) {

	StockChange toChange() {
		return new StockChange(optionId, quantity);
	}
}
