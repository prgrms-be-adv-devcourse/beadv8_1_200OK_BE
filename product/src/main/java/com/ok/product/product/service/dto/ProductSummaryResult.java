package com.ok.product.product.service.dto;

import com.ok.product.product.domain.Product;

public record ProductSummaryResult(
	Long productId,
	String name,
	String thumbnailUrl,
	Long minSalePrice,
	Long minPriceListPrice,
	int discountRate,
	ProductCategoryCode category,
	ProductRegionCode region,
	ProductStatusCode status,
	boolean soldOut,
	int reviewCount,
	double averageRating
) {

	public static ProductSummaryResult from(Product product, String thumbnailUrl) {
		return new ProductSummaryResult(product.getId(), product.getName(), thumbnailUrl,
			product.getMinSalePrice(), product.getMinPriceListPrice(), product.discountRate(), ProductCategoryCode.from(product.getCategory()),
			ProductRegionCode.from(product.getRegion()), ProductStatusCode.from(product.getStatus()), product.isSoldOut(), product.getReviewCount(), product.averageRating());
	}
}
