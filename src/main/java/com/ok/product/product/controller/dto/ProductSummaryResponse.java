package com.ok.product.product.controller.dto;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.service.dto.ProductSummaryResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 목록 항목")
public record ProductSummaryResponse(

	@Schema(description = "상품 ID", example = "1")
	Long productId,

	@Schema(description = "상품명", example = "청송 꿀사과")
	String name,

	@Schema(description = "대표 이미지 URL")
	String thumbnailUrl,

	@Schema(description = "옵션 중 최저 판매가 (원)", example = "32000")
	Long minSalePrice,

	@Schema(description = "최저 판매가 옵션의 정가 (원)", example = "40000")
	Long minPriceListPrice,

	@Schema(description = "최저 판매가 옵션의 할인율 (%, 소수점 버림)", example = "20")
	int discountRate,

	@Schema(description = "하위 카테고리", example = "FRUIT")
	ProductCategory category,

	@Schema(description = "산지 (시·도)", example = "GYEONGBUK")
	ProductRegion region,

	@Schema(description = "판매 상태. 화면은 ON_SALE이 아니면 판매 예정·일시 중지·판매 종료로 표시한다", example = "ON_SALE")
	ProductStatus status,

	@Schema(description = "품절 여부 (모든 옵션의 판매 가능 수량이 0)", example = "false")
	boolean soldOut,

	@Schema(description = "리뷰 수", example = "12")
	int reviewCount,

	@Schema(description = "평균 별점 (소수 첫째 자리, 리뷰가 없으면 0)", example = "4.5")
	double averageRating
) {

	public static ProductSummaryResponse from(ProductSummaryResult result) {
		return new ProductSummaryResponse(result.productId(), result.name(), result.thumbnailUrl(),
			result.minSalePrice(), result.minPriceListPrice(), result.discountRate(), result.category(),
			result.region(), result.status(), result.soldOut(), result.reviewCount(), result.averageRating());
	}
}
