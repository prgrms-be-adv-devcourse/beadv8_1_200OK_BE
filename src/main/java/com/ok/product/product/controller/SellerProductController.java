package com.ok.product.product.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.product.product.controller.dto.ProductPageResponse;
import com.ok.product.product.controller.dto.SellerProductDetailResponse;
import com.ok.product.product.controller.dto.SellerProductSummaryResponse;
import com.ok.product.product.service.ProductService;
import com.ok.product.product.service.dto.ProductStatusCode;
import com.ok.product.product.service.dto.ProductVisibilityCode;
import com.ok.product.product.service.dto.SellerProductSearchQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@PreAuthorize("hasRole('SELLER')")
@RestController
@RequestMapping("/api/v1/sellers/me/products")
@RequiredArgsConstructor
public class SellerProductController implements SellerProductControllerSpec {

	private final ProductService productService;

	@Override
	@GetMapping
	public ResponseEntity<ProductPageResponse<SellerProductSummaryResponse>> search(
		@CurrentMemberId Long memberId,
		@RequestParam(required = false) ProductStatusCode status,
		@RequestParam(required = false) ProductVisibilityCode visibility,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		SellerProductSearchQuery query = new SellerProductSearchQuery(status, visibility, page, size);
		return ResponseEntity.ok(ProductPageResponse.from(
			productService.searchMyProducts(memberId, query).map(SellerProductSummaryResponse::from)));
	}

	@Override
	@GetMapping("/{productId}")
	public ResponseEntity<SellerProductDetailResponse> get(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId
	) {
		return ResponseEntity.ok(SellerProductDetailResponse.from(productService.getMyProduct(memberId, productId)));
	}
}
