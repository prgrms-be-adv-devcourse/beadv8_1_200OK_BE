package com.ok.product.product.service.dto;

import com.ok.product.product.domain.Product;

import java.time.LocalDateTime;

// 판매자 관리 화면용 목록 항목.
public record SellerProductSummaryResult(
	Long productId,
	String name,
	String thumbnailUrl,
	Long minSalePrice,
	Long minPriceListPrice,
	int discountRate,
	ProductCategoryCode category,
	ProductRegionCode region,
	ProductStatusCode status,
	ProductVisibilityCode visibility,
	int availableStock,
	boolean soldOut,
	int reviewCount,
	double averageRating,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {

	public static SellerProductSummaryResult from(Product product, String thumbnailUrl) {
		return new SellerProductSummaryResult(product.getId(), product.getName(), thumbnailUrl,
			product.getMinSalePrice(), product.getMinPriceListPrice(), product.discountRate(),
			ProductCategoryCode.from(product.getCategory()), ProductRegionCode.from(product.getRegion()),
			ProductStatusCode.from(product.getStatus()), ProductVisibilityCode.from(product.getVisibility()),
			product.availableStock(), product.isSoldOut(), product.getReviewCount(), product.averageRating(),
			product.getCreatedAt(), product.getUpdatedAt());
	}
}
