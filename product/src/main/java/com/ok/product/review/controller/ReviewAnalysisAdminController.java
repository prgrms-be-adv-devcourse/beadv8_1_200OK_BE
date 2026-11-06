package com.ok.product.review.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ok.product.review.controller.dto.ReviewAnalysisPreviewRequest;
import com.ok.product.review.controller.dto.ReviewKeywordPreviewResponse;
import com.ok.product.review.controller.dto.ReviewSentimentPreviewResponse;
import com.ok.product.review.service.ReviewAnalysisPreviewFacade;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/review-analysis")
@RequiredArgsConstructor
public class ReviewAnalysisAdminController implements ReviewAnalysisAdminControllerSpec {

	private final ReviewAnalysisPreviewFacade reviewAnalysisPreviewFacade;

	@Override
	@PostMapping("/keywords")
	public ResponseEntity<ReviewKeywordPreviewResponse> analyzeKeywords(@Valid @RequestBody ReviewAnalysisPreviewRequest request) {
		return ResponseEntity.ok(ReviewKeywordPreviewResponse.from(reviewAnalysisPreviewFacade.analyzeKeywords(request.toQuery())));
	}

	@Override
	@PostMapping("/sentiment")
	public ResponseEntity<ReviewSentimentPreviewResponse> analyzeSentiment(@Valid @RequestBody ReviewAnalysisPreviewRequest request) {
		return ResponseEntity.ok(ReviewSentimentPreviewResponse.from(reviewAnalysisPreviewFacade.analyzeSentiment(request.toQuery())));
	}
}
