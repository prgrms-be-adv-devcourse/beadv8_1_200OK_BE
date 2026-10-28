package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.StockChange;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "재고 배정 요청")
public record StockAllocationRequest(

	@Schema(description = "주문 ID. 같은 주문 ID로 다시 보내면 items는 비교하지 않고 첫 배정을 유지한다", example = "10")
	@NotNull(message = "주문 ID는 필수입니다.")
	Long orderId,

	@Schema(description = "배정할 옵션과 수량")
	@NotEmpty(message = "배정 항목은 1개 이상이어야 합니다.")
	List<@NotNull(message = "배정 항목은 비울 수 없습니다.") @Valid StockAllocationItemRequest> items
) {

	public List<StockChange> toChanges() {
		return items.stream().map(StockAllocationItemRequest::toChange).toList();
	}
}
