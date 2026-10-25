package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.ProductOptionResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 옵션 (구매자용, 재고 수량 대신 품절 여부만)")
public record ProductOptionResponse(

	@Schema(description = "옵션 ID. 장바구니·주문에 쓴다", example = "11")
	Long optionId,

	@Schema(description = "옵션명", example = "3kg 특품")
	String name,

	@Schema(description = "정가 (원)", example = "40000")
	Long listPrice,

	@Schema(description = "판매가 (원)", example = "32000")
	Long salePrice,

	@Schema(description = "할인율 (%, 소수점 버림)", example = "20")
	int discountRate,

	@Schema(description = "품절 여부", example = "false")
	boolean soldOut
) {

	public static ProductOptionResponse from(ProductOptionResult result) {
		return new ProductOptionResponse(result.optionId(), result.name(), result.listPrice(), result.salePrice(),
			result.discountRate(), result.soldOut());
	}
}
