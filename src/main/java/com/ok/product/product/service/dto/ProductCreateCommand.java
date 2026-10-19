package com.ok.product.product.service.dto;

import java.util.List;

public record ProductCreateCommand(
	String name,
	String descriptionHtml,
	ProductCategoryCode category,
	ProductRegionCode region,
	ProductStatusCode status,
	ProductVisibilityCode visibility,
	List<String> imageUrls,
	List<ProductOptionCommand> options
) {
}
