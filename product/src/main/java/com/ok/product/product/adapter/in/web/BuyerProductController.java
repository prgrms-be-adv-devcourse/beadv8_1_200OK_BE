package com.ok.product.product.adapter.in.web;

import com.ok.product.product.adapter.in.web.dto.ProductCategoryGroupResponse;
import com.ok.product.product.adapter.in.web.dto.ProductDetailResponse;
import com.ok.product.product.adapter.in.web.dto.ProductPageResponse;
import com.ok.product.product.adapter.in.web.dto.ProductSummaryResponse;
import com.ok.product.product.application.port.in.BuyerProductUseCase;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ProductCategoryGroupCode;
import com.ok.product.product.application.dto.ProductRegionCode;
import com.ok.product.product.application.dto.ProductSearchQuery;
import com.ok.product.product.application.dto.ProductSortCode;
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

	private final BuyerProductUseCase buyerProductUseCase;

	@Override
	@GetMapping
	public ResponseEntity<ProductPageResponse<ProductSummaryResponse>> search(
		@RequestParam(required = false) String keyword,
		@RequestParam(required = false) ProductCategoryGroupCode categoryGroup,
		@RequestParam(required = false) ProductCategoryCode category,
		@RequestParam(required = false) ProductRegionCode region,
		@RequestParam(required = false) ProductSortCode sort,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		ProductSearchQuery query = new ProductSearchQuery(keyword, categoryGroup, category, region, sort, page, size);
		return ResponseEntity.ok(
			ProductPageResponse.from(buyerProductUseCase.searchVisibleProducts(query).map(ProductSummaryResponse::from)));
	}

	@Override
	@GetMapping("/{productId}")
	public ResponseEntity<ProductDetailResponse> getProduct(@PathVariable Long productId) {
		return ResponseEntity.ok(ProductDetailResponse.from(buyerProductUseCase.getVisibleProduct(productId)));
	}

	@Override
	@GetMapping("/categories")
	public ResponseEntity<List<ProductCategoryGroupResponse>> getCategories() {
		return ResponseEntity.ok(buyerProductUseCase.getCategories().stream()
			.map(ProductCategoryGroupResponse::from)
			.toList());
	}
}
