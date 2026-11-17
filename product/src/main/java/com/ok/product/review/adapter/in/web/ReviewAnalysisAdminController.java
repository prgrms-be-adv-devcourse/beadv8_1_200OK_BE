package com.ok.product.review.adapter.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ok.product.review.adapter.in.web.dto.ReviewAnalysisPreviewRequest;
import com.ok.product.review.adapter.in.web.dto.ReviewKeywordPreviewResponse;
import com.ok.product.review.adapter.in.web.dto.ReviewSentimentPreviewResponse;
import com.ok.product.review.application.port.in.ReviewAnalysisPreviewUseCase;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/review-analysis")
@RequiredArgsConstructor
public class ReviewAnalysisAdminController implements ReviewAnalysisAdminControllerSpec {

	private final ReviewAnalysisPreviewUseCase reviewAnalysisPreviewUseCase;

	@Override
	@PostMapping("/keywords")
	public ResponseEntity<ReviewKeywordPreviewResponse> analyzeKeywords(@Valid @RequestBody ReviewAnalysisPreviewRequest request) {
		return ResponseEntity.ok(ReviewKeywordPreviewResponse.from(reviewAnalysisPreviewUseCase.analyzeKeywords(request.toQuery())));
	}

	@Override
	@PostMapping("/sentiment")
	public ResponseEntity<ReviewSentimentPreviewResponse> analyzeSentiment(@Valid @RequestBody ReviewAnalysisPreviewRequest request) {
		return ResponseEntity.ok(ReviewSentimentPreviewResponse.from(reviewAnalysisPreviewUseCase.analyzeSentiment(request.toQuery())));
	}
}
