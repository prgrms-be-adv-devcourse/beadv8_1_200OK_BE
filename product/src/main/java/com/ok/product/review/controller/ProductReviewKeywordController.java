package com.ok.product.review.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ok.product.review.controller.dto.ProductReviewKeywordsResponse;
import com.ok.product.review.service.ReviewKeywordService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductReviewKeywordController implements ProductReviewKeywordControllerSpec {

	private final ReviewKeywordService reviewKeywordService;

	@Override
	@GetMapping("/{productId}/review-keywords")
	public ResponseEntity<ProductReviewKeywordsResponse> getKeywords(@PathVariable Long productId) {
		return ResponseEntity.ok(ProductReviewKeywordsResponse.from(reviewKeywordService.getKeywords(productId)));
	}
}
