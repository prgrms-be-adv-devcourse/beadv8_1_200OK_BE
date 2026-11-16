package com.ok.product.review.adapter.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ok.common.security.CurrentMemberId;
import com.ok.product.review.adapter.in.web.dto.ReviewSentimentSummaryResponse;
import com.ok.product.review.application.port.in.ReviewSentimentSummaryUseCase;

import lombok.RequiredArgsConstructor;

@PreAuthorize("hasRole('SELLER')")
@RestController
@RequestMapping("/api/v1/sellers/me/products")
@RequiredArgsConstructor
public class SellerReviewSentimentController implements SellerReviewSentimentControllerSpec {

	private final ReviewSentimentSummaryUseCase reviewSentimentSummaryUseCase;

	@Override
	@GetMapping("/{productId}/review-sentiments")
	public ResponseEntity<ReviewSentimentSummaryResponse> getSummary(
		@CurrentMemberId Long memberId,
		@PathVariable Long productId
	) {
		return ResponseEntity.ok(ReviewSentimentSummaryResponse.from(reviewSentimentSummaryUseCase.getSummary(memberId, productId)));
	}
}
