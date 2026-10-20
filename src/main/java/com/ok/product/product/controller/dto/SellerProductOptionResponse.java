package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.SellerProductOptionResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 옵션 (판매자 수정 화면용)")
public record SellerProductOptionResponse(

	@Schema(description = "옵션 ID. 수정 요청의 옵션 id로 그대로 보낸다", example = "11")
	Long optionId,

	@Schema(description = "옵션명", example = "3kg 특품")
	String name,

	@Schema(description = "정가 (원)", example = "40000")
	Long listPrice,

	@Schema(description = "판매가 (원)", example = "32000")
	Long salePrice,

	@Schema(description = "총재고", example = "150")
	int totalStock,

	@Schema(description = "주문에 배정된 수량. 총재고를 이보다 작게 줄일 수 없다", example = "55")
	int allocatedQuantity,

	@Schema(description = "판매 가능 수량 (총재고 - 배정 수량)", example = "95")
	int availableStock
) {

	public static SellerProductOptionResponse from(SellerProductOptionResult result) {
		return new SellerProductOptionResponse(result.optionId(), result.name(), result.listPrice(),
			result.salePrice(), result.totalStock(), result.allocatedQuantity(), result.availableStock());
	}
}
