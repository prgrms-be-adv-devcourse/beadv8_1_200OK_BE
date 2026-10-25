package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.ProductOptionCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(description = "상품 옵션 (수정)")
public record ProductOptionUpdateRequest(

	@Schema(description = "기존 옵션 id. 새 옵션이면 생략합니다. 요청에 없는 기존 옵션은 삭제됩니다.", example = "11", nullable = true)
	Long id,

	@Schema(description = "옵션명 (상품 안에서 중복 불가)", example = "3kg 특품")
	@NotBlank(message = "옵션명은 필수입니다.")
	@Size(max = 100, message = "옵션명은 100자 이하여야 합니다.")
	String name,

	@Schema(description = "정가(원). 생략하면 판매가와 같게 저장됩니다.", example = "40000", nullable = true)
	@Positive(message = "정가는 0원보다 커야 합니다.")
	@Max(value = 100_000_000, message = "정가는 1억 원 이하여야 합니다.")
	Long listPrice,

	@Schema(description = "판매가(원). 정가보다 클 수 없습니다.", example = "30000")
	@NotNull(message = "판매가는 필수입니다.")
	@Positive(message = "판매가는 0원보다 커야 합니다.")
	@Max(value = 100_000_000, message = "판매가는 1억 원 이하여야 합니다.")
	Long salePrice,

	@Schema(description = "총재고. 주문에 배정된 수량보다 작게 줄일 수 없습니다.", example = "150")
	@NotNull(message = "총재고는 필수입니다.")
	@PositiveOrZero(message = "총재고는 0 이상이어야 합니다.")
	Integer totalStock
) {

	ProductOptionCommand toCommand() {
		return new ProductOptionCommand(id, name, listPrice, salePrice, totalStock);
	}
}
