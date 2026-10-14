package com.ok.product.product.service.dto;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductImage;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

public record ProductDetailResult(
	Long productId,
	String name,
	String descriptionHtml,
	ProductCategory category,
	ProductRegion region,
	ProductStatus status,
	Long minSalePrice,
	Long minPriceListPrice,
	int discountRate,
	boolean soldOut,
	int reviewCount,
	double averageRating,
	List<String> imageUrls,
	List<ProductOptionResult> options,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {

	public static ProductDetailResult from(Product product) {
		List<String> imageUrls = product.getImages().stream()
			.sorted(Comparator.comparingInt(ProductImage::getSortOrder))
			.map(ProductImage::getUrl)
			.toList();
		List<ProductOptionResult> options = product.activeOptions().stream()
			.map(ProductOptionResult::from)
			.toList();
		return new ProductDetailResult(product.getId(), product.getName(),
			product.getDescriptionHtml(), product.getCategory(), product.getRegion(), product.getStatus(),
			product.getMinSalePrice(), product.getMinPriceListPrice(), product.discountRate(), product.isSoldOut(),
			product.getReviewCount(), product.averageRating(), imageUrls, options, product.getCreatedAt(),
			product.getUpdatedAt());
	}
}
