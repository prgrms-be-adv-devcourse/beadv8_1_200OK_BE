package com.ok.product.product.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.service.dto.ProductDetailResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 상세 (구매자용)")
public record ProductDetailResponse(

	@Schema(description = "상품 ID", example = "1")
	Long productId,

	@Schema(description = "상품명", example = "청송 꿀사과")
	String name,

	@Schema(description = "상세설명 HTML (저장할 때 정제됨)")
	String descriptionHtml,

	@Schema(description = "상위 카테고리", example = "AGRICULTURE")
	ProductCategoryGroup categoryGroup,

	@Schema(description = "하위 카테고리", example = "FRUIT")
	ProductCategory category,

	@Schema(description = "산지 (시·도)", example = "GYEONGBUK")
	ProductRegion region,

	@Schema(description = "판매 상태. ON_SALE일 때만 구매할 수 있다", example = "ON_SALE")
	ProductStatus status,

	@Schema(description = "옵션 중 최저 판매가 (원)", example = "32000")
	Long minSalePrice,

	@Schema(description = "최저 판매가 옵션의 정가 (원)", example = "40000")
	Long minPriceListPrice,

	@Schema(description = "최저 판매가 옵션의 할인율 (%, 소수점 버림)", example = "20")
	int discountRate,

	@Schema(description = "품절 여부 (모든 옵션이 품절)", example = "false")
	boolean soldOut,

	@Schema(description = "리뷰 수", example = "12")
	int reviewCount,

	@Schema(description = "평균 별점 (소수 첫째 자리, 리뷰가 없으면 0)", example = "4.5")
	double averageRating,

	@Schema(description = "이미지 URL. 첫 장이 대표 이미지")
	List<String> imageUrls,

	@Schema(description = "옵션 (화면 순서)")
	List<ProductOptionResponse> options,

	@Schema(description = "등록 시각")
	LocalDateTime createdAt,

	@Schema(description = "마지막 수정 시각")
	LocalDateTime updatedAt
) {

	public static ProductDetailResponse from(ProductDetailResult result) {
		return new ProductDetailResponse(result.productId(), result.name(),
			result.descriptionHtml(), result.category().getGroup(), result.category(), result.region(),
			result.status(), result.minSalePrice(), result.minPriceListPrice(), result.discountRate(),
			result.soldOut(), result.reviewCount(), result.averageRating(), result.imageUrls(),
			result.options().stream().map(ProductOptionResponse::from).toList(), result.createdAt(),
			result.updatedAt());
	}
}
