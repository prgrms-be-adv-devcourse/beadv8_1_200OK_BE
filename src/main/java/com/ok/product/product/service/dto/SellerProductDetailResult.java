package com.ok.product.product.service.dto;

import com.ok.product.product.domain.Product;

import java.time.LocalDateTime;
import java.util.List;

// 판매자 수정 화면용 상세. 상품 수정 요청에 그대로 다시 보낼 version을 포함한다
public record SellerProductDetailResult(
	Long productId,
	Integer version,
	String name,
	String descriptionHtml,
	ProductCategoryGroupCode categoryGroup,
	ProductCategoryCode category,
	ProductRegionCode region,
	ProductStatusCode status,
	ProductVisibilityCode visibility,
	List<String> imageUrls,
	List<SellerProductOptionResult> options,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {

	// 삭제된 옵션을 내려주면 프런트가 수정 요청에 다시 실어 보내 되살릴 수 있어서 활성 옵션만 준다
	public static SellerProductDetailResult from(Product product, List<String> imageUrls) {
		List<SellerProductOptionResult> options = product.activeOptions().stream()
			.map(SellerProductOptionResult::from)
			.toList();
		return new SellerProductDetailResult(product.getId(), product.getVersion(), product.getName(),
			product.getDescriptionHtml(), ProductCategoryGroupCode.from(product.getCategory().getGroup()),
			ProductCategoryCode.from(product.getCategory()), ProductRegionCode.from(product.getRegion()),
			ProductStatusCode.from(product.getStatus()), ProductVisibilityCode.from(product.getVisibility()),
			imageUrls, options, product.getCreatedAt(), product.getUpdatedAt());
	}
}
