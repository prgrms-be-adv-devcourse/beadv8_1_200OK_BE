package com.ok.product.review.adapter.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ok.product.review.adapter.in.web.dto.ProductReviewKeywordsResponse;
import com.ok.product.review.application.port.in.ProductReviewKeywordUseCase;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductReviewKeywordController implements ProductReviewKeywordControllerSpec {

	private final ProductReviewKeywordUseCase productReviewKeywordUseCase;

	@Override
	@GetMapping("/{productId}/review-keywords")
	public ResponseEntity<ProductReviewKeywordsResponse> getKeywords(@PathVariable Long productId) {
		return ResponseEntity.ok(ProductReviewKeywordsResponse.from(productReviewKeywordUseCase.getKeywords(productId)));
	}
}
