package com.ok.product.review.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.review.controller.dto.ReviewSentimentSummaryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Review Sentiment (Seller)", description = "판매자 리뷰 감성 집계 API")
interface SellerReviewSentimentControllerSpec {

	@Operation(summary = "내 상품 리뷰 감성 집계 조회", description = """
		내 상품의 분석 완료 리뷰를 전체 감성과 여섯 분석 항목별로 셉니다.
		분석 완료 리뷰는 삭제되지 않았고 감성 분석 결과가 있는 리뷰입니다. 분석을 기다리거나 분석에 실패한 리뷰는 어디에도 세지 않습니다.
		항목별 개수의 분모는 그 항목을 언급한 리뷰 수(mentionedReviewCount)이고, 언급하지 않은 리뷰는 들지 않습니다.
		최소 리뷰 수 기준은 없어 리뷰가 적어도 그대로 보여 줍니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "403", description = "SELLER가 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됐거나 내 상품이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ReviewSentimentSummaryResponse> getSummary(
		Long memberId,
		@Parameter(description = "상품 ID", example = "1") Long productId
	);
}
