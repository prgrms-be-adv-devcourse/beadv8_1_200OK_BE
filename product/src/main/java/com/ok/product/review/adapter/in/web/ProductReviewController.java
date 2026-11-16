package com.ok.product.review.adapter.in.web;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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
import com.ok.product.review.adapter.in.web.dto.ProductPageResponse;
import com.ok.product.review.adapter.in.web.dto.ProductReviewCreateRequest;
import com.ok.product.review.adapter.in.web.dto.ProductReviewCreateResponse;
import com.ok.product.review.adapter.in.web.dto.ProductReviewResponse;
import com.ok.product.review.adapter.in.web.dto.ProductReviewUpdateRequest;
import com.ok.product.review.application.port.in.ProductReviewCreateUseCase;
import com.ok.product.review.application.port.in.ProductReviewUseCase;
import com.ok.product.review.application.dto.ProductReviewResult;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProductReviewController implements ProductReviewControllerSpec {

	private final ProductReviewCreateUseCase productReviewCreateUseCase;
	private final ProductReviewUseCase productReviewUseCase;

	@Override
	@PostMapping("/products/{productId}/reviews")
	public ResponseEntity<ProductReviewCreateResponse> create(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId,
		@RequestBody ProductReviewCreateRequest request
	) {
		Long reviewId = productReviewCreateUseCase.create(memberId, productId, request.toCommand());
		return ResponseEntity.status(HttpStatus.CREATED).body(new ProductReviewCreateResponse(reviewId));
	}

	@Override
	@PutMapping("/reviews/{reviewId}")
	public ResponseEntity<Void> update(
		@CurrentMemberId Long memberId,
		@PathVariable Long reviewId,
		@RequestBody ProductReviewUpdateRequest request
	) {
		productReviewUseCase.update(memberId, reviewId, request.toCommand());
		return ResponseEntity.noContent().build();
	}

	@Override
	@DeleteMapping("/reviews/{reviewId}")
	public ResponseEntity<Void> delete(
		@CurrentMemberId Long memberId,
		@PathVariable Long reviewId
	) {
		productReviewUseCase.delete(memberId, reviewId);
		return ResponseEntity.noContent().build();
	}

	@Override
	@GetMapping("/products/{productId}/reviews")
	public ResponseEntity<ProductPageResponse<ProductReviewResponse>> getProductReviews(
		@PathVariable Long productId,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size,
		@RequestParam(required = false) String keyword
	) {
		PageRequest pageable = PageRequest.of(page, size);
		// 칩 선택을 해제한 화면이 빈 keyword를 보내도 전체 목록이 나오게 빈 값은 필터 없음으로 본다
		Page<ProductReviewResult> reviews = StringUtils.hasText(keyword)
			? productReviewUseCase.getProductReviewsByKeyword(productId, keyword.strip(), pageable)
			: productReviewUseCase.getProductReviews(productId, pageable);
		return ResponseEntity.ok(ProductPageResponse.from(reviews.map(ProductReviewResponse::from)));
	}

	@Override
	@GetMapping("/products/{productId}/reviews/me")
	public ResponseEntity<List<ProductReviewResponse>> getMyReviewsOfProduct(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId
	) {
		return ResponseEntity.ok(productReviewUseCase.getMyReviewsOfProduct(memberId, productId).stream()
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
			productReviewUseCase.getMyReviews(memberId, PageRequest.of(page, size)).map(ProductReviewResponse::from)));
	}
}
