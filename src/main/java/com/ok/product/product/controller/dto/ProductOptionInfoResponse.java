package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.ProductOptionInfoResult;
import com.ok.product.product.service.dto.ProductStatusCode;
import com.ok.product.product.service.dto.ProductVisibilityCode;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "옵션 정보 (order 모듈용)")
public record ProductOptionInfoResponse(

	@Schema(description = "옵션 ID", example = "11")
	Long optionId,

	@Schema(description = "상품 ID", example = "1")
	Long productId,

	@Schema(description = "판매자 회원 ID. 본인 상품 여부 확인에 쓴다", example = "7")
	Long sellerId,

	@Schema(description = "상품명", example = "청송 꿀사과")
	String productName,

	@Schema(description = "옵션명", example = "3kg 특품")
	String optionName,

	@Schema(description = "상품 대표 이미지 URL (옵션별 이미지 없음)", example = "https://bucket.s3.ap-northeast-2.amazonaws.com/product/a.jpg")
	String productThumbnailUrl,

	@Schema(description = "정가 (원)", example = "40000")
	Long listPrice,

	@Schema(description = "판매가 (원)", example = "32000")
	Long salePrice,

	@Schema(description = "상품 판매 상태", example = "ON_SALE")
	ProductStatusCode status,

	@Schema(description = "상품 노출 여부", example = "VISIBLE")
	ProductVisibilityCode visibility,

	@Schema(description = "상품 삭제 여부", example = "false")
	boolean productDeleted,

	@Schema(description = "옵션 삭제 여부", example = "false")
	boolean optionDeleted,

	@Schema(description = "판매 가능 수량 (총재고 - 배정 수량)", example = "147")
	int availableStock,

	@Schema(description = "지금 구매할 수 있는지. 판매 중, 노출, 상품·옵션 미삭제, 판매 가능 수량 1개 이상을 모두 만족하면 true. "
		+ "담을 수량과의 비교와 본인 상품 여부는 호출하는 쪽이 확인한다", example = "true")
	boolean purchasable
) {

	public static ProductOptionInfoResponse from(ProductOptionInfoResult result) {
		return new ProductOptionInfoResponse(result.optionId(), result.productId(), result.sellerId(),
			result.productName(), result.optionName(), result.productThumbnailUrl(), result.listPrice(), result.salePrice(),
			result.status(), result.visibility(), result.productDeleted(), result.optionDeleted(),
			result.availableStock(), result.purchasable());
	}
}
