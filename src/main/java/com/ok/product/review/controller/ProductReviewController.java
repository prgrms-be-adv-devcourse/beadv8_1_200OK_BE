package com.ok.product.review.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ok.common.security.CurrentMemberId;
import com.ok.product.product.controller.dto.ProductPageResponse;
import com.ok.product.review.controller.dto.ProductReviewCreateRequest;
import com.ok.product.review.controller.dto.ProductReviewCreateResponse;
import com.ok.product.review.controller.dto.ProductReviewResponse;
import com.ok.product.review.controller.dto.ProductReviewUpdateRequest;
import com.ok.product.review.service.ProductReviewService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProductReviewController implements ProductReviewControllerSpec {

	private final ProductReviewService productReviewService;

	@Override
	@PostMapping("/products/{productId}/reviews")
	public ResponseEntity<ProductReviewCreateResponse> create(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId,
		@RequestBody ProductReviewCreateRequest request
	) {
		Long reviewId = productReviewService.create(memberId, productId, request.toCommand());
		return ResponseEntity.status(HttpStatus.CREATED).body(new ProductReviewCreateResponse(reviewId));
	}

	@Override
	@PutMapping("/reviews/{reviewId}")
	public ResponseEntity<Void> update(
		@CurrentMemberId Long memberId,
		@PathVariable Long reviewId,
		@RequestBody ProductReviewUpdateRequest request
	) {
		productReviewService.update(memberId, reviewId, request.toCommand());
		return ResponseEntity.noContent().build();
	}

	@Override
	@DeleteMapping("/reviews/{reviewId}")
	public ResponseEntity<Void> delete(
		@CurrentMemberId Long memberId,
		@PathVariable Long reviewId
	) {
		productReviewService.delete(memberId, reviewId);
		return ResponseEntity.noContent().build();
	}

	@Override
	@GetMapping("/products/{productId}/reviews")
	public ResponseEntity<ProductPageResponse<ProductReviewResponse>> getProductReviews(
		@PathVariable Long productId,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return ResponseEntity.ok(ProductPageResponse.from(
			productReviewService.getProductReviews(productId, PageRequest.of(page, size)).map(ProductReviewResponse::from)));
	}

	@Override
	@GetMapping("/products/{productId}/reviews/me")
	public ResponseEntity<List<ProductReviewResponse>> getMyReviewsOfProduct(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId
	) {
		return ResponseEntity.ok(productReviewService.getMyReviewsOfProduct(memberId, productId).stream()
			.map(ProductReviewResponse::from)
			.toList());
	}

	@Override
	@GetMapping("/reviews/me")
	public ResponseEntity<ProductPageResponse<ProductReviewResponse>> getMyReviews(
		@CurrentMemberId Long memberId,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return ResponseEntity.ok(ProductPageResponse.from(
			productReviewService.getMyReviews(memberId, PageRequest.of(page, size)).map(ProductReviewResponse::from)));
	}
}
