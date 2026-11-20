package com.ok.product.product.adapter.in.web.dto;

import com.ok.product.product.application.dto.ProductOptionResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 옵션 (구매자용, 재고 수량 대신 품절·품절 임박 여부만)")
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
	boolean soldOut,

	@Schema(description = "품절 임박 여부. 남은 재고가 서버가 정한 기준 이하이면 true다. 품절(soldOut)이면 false다", example = "false")
	boolean lowStock
) {

	public static ProductOptionResponse from(ProductOptionResult result) {
		return new ProductOptionResponse(result.optionId(), result.name(), result.listPrice(), result.salePrice(),
			result.discountRate(), result.soldOut(), result.lowStock());
	}
}
