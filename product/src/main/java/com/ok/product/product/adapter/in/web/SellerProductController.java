package com.ok.product.product.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.product.product.adapter.in.web.dto.ProductPageResponse;
import com.ok.product.product.adapter.in.web.dto.SellerProductDetailResponse;
import com.ok.product.product.adapter.in.web.dto.SellerProductSummaryResponse;
import com.ok.product.product.application.port.in.SellerProductUseCase;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.dto.ProductVisibilityCode;
import com.ok.product.product.application.dto.SellerProductSearchQuery;
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

	private final SellerProductUseCase sellerProductUseCase;

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
			sellerProductUseCase.searchMyProducts(memberId, query).map(SellerProductSummaryResponse::from)));
	}

	@Override
	@GetMapping("/{productId}")
	public ResponseEntity<SellerProductDetailResponse> get(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId
	) {
		return ResponseEntity.ok(SellerProductDetailResponse.from(sellerProductUseCase.getMyProduct(memberId, productId)));
	}
}
