package com.ok.product.product.controller;

import com.ok.product.product.controller.dto.ProductCategoryGroupResponse;
import com.ok.product.product.controller.dto.ProductDetailResponse;
import com.ok.product.product.controller.dto.ProductPageResponse;
import com.ok.product.product.controller.dto.ProductSummaryResponse;
import com.ok.product.product.service.ProductService;
import com.ok.product.product.service.dto.ProductCategoryCode;
import com.ok.product.product.service.dto.ProductCategoryGroupCode;
import com.ok.product.product.service.dto.ProductRegionCode;
import com.ok.product.product.service.dto.ProductSearchQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 구매자용 조회
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class BuyerProductController implements BuyerProductControllerSpec {

	private final ProductService productService;

	@Override
	@GetMapping
	public ResponseEntity<ProductPageResponse<ProductSummaryResponse>> search(
		@RequestParam(required = false) ProductCategoryGroupCode categoryGroup,
		@RequestParam(required = false) ProductCategoryCode category,
		@RequestParam(required = false) ProductRegionCode region,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		ProductSearchQuery query = new ProductSearchQuery(categoryGroup, category, region, page, size);
		return ResponseEntity.ok(
			ProductPageResponse.from(productService.searchVisibleProducts(query).map(ProductSummaryResponse::from)));
	}

	@Override
	@GetMapping("/{productId}")
	public ResponseEntity<ProductDetailResponse> getProduct(@PathVariable Long productId) {
		return ResponseEntity.ok(ProductDetailResponse.from(productService.getVisibleProduct(productId)));
	}

	@Override
	@GetMapping("/categories")
	public ResponseEntity<List<ProductCategoryGroupResponse>> getCategories() {
		return ResponseEntity.ok(productService.getCategories().stream()
			.map(ProductCategoryGroupResponse::from)
			.toList());
	}
}
