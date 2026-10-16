package com.ok.product.product.service.dto;

import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;

// order 모듈용 옵션 정보. 구매자 화면과 달리 판매자 ID와 재고 수량까지 준다
public record ProductOptionInfoResult(
	Long optionId,
	Long productId,
	Long sellerId,
	String productName,
	String optionName,
	String productThumbnailUrl,
	Long listPrice,
	Long salePrice,
	ProductStatus status,
	ProductVisibility visibility,
	boolean productDeleted,
	boolean optionDeleted,
	int availableStock,
	boolean purchasable
) {

	public static ProductOptionInfoResult from(ProductOption option) {
		Product product = option.getProduct();
		return new ProductOptionInfoResult(option.getId(), product.getId(), product.getSellerId(), product.getName(),
			option.getName(), product.getThumbnailUrl(), option.getListPrice(), option.getSalePrice(),
			product.getStatus(), product.getVisibility(), product.isDeleted(), option.isDeleted(),
			option.availableStock(), option.isPurchasable());
	}
}
