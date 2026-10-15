package com.ok.product.product.service.dto;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;

public record ProductSummaryResult(
	Long productId,
	String name,
	String thumbnailUrl,
	Long minSalePrice,
	Long minPriceListPrice,
	int discountRate,
	ProductCategory category,
	ProductRegion region,
	ProductStatus status,
	boolean soldOut,
	int reviewCount,
	double averageRating
) {

	public static ProductSummaryResult from(Product product, String thumbnailUrl) {
		return new ProductSummaryResult(product.getId(), product.getName(), thumbnailUrl,
			product.getMinSalePrice(), product.getMinPriceListPrice(), product.discountRate(), product.getCategory(),
			product.getRegion(), product.getStatus(), product.isSoldOut(), product.getReviewCount(), product.averageRating());
	}
}
