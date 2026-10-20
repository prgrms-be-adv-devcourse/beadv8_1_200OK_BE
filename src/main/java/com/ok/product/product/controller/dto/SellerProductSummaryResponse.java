package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.ProductCategoryCode;
import com.ok.product.product.service.dto.ProductRegionCode;
import com.ok.product.product.service.dto.ProductStatusCode;
import com.ok.product.product.service.dto.ProductVisibilityCode;
import com.ok.product.product.service.dto.SellerProductSummaryResult;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "판매자 상품 목록 항목")
public record SellerProductSummaryResponse(

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
	ProductCategoryCode category,

	@Schema(description = "산지 (시·도)", example = "GYEONGBUK")
	ProductRegionCode region,

	@Schema(description = "판매 상태", example = "ON_SALE")
	ProductStatusCode status,

	@Schema(description = "노출 여부", example = "VISIBLE")
	ProductVisibilityCode visibility,

	@Schema(description = "판매 가능 수량 (삭제되지 않은 옵션의 총재고 - 배정 수량 합)", example = "95")
	int availableStock,

	@Schema(description = "품절 여부 (판매 가능 수량이 0)", example = "false")
	boolean soldOut,

	@Schema(description = "리뷰 수", example = "12")
	int reviewCount,

	@Schema(description = "평균 별점 (소수 첫째 자리, 리뷰가 없으면 0)", example = "4.5")
	double averageRating,

	@Schema(description = "등록 시각")
	LocalDateTime createdAt,

	@Schema(description = "마지막 수정 시각")
	LocalDateTime updatedAt
) {

	public static SellerProductSummaryResponse from(SellerProductSummaryResult result) {
		return new SellerProductSummaryResponse(result.productId(), result.name(), result.thumbnailUrl(),
			result.minSalePrice(), result.minPriceListPrice(), result.discountRate(), result.category(),
			result.region(), result.status(), result.visibility(), result.availableStock(), result.soldOut(),
			result.reviewCount(), result.averageRating(), result.createdAt(), result.updatedAt());
	}
}
