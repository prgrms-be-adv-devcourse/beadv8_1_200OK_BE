package com.ok.product.review.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.review.controller.dto.ProductReviewKeywordsResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Review Keyword", description = "구매자 리뷰 키워드 API")
interface ProductReviewKeywordControllerSpec {

	@Operation(summary = "상품 리뷰 키워드 조회", description = """
		여러 구매자가 반복해 칭찬한 키워드를 상품 상세의 칩으로 보여 주기 위해 조회합니다. 로그인하지 않아도 됩니다.
		분석 완료 리뷰는 삭제되지 않았고 지금 내용으로 키워드 분석을 마친 리뷰입니다. 수정한 뒤 다시 분석하기 전인 리뷰는 세지 않습니다.
		분석 완료 리뷰가 10개 이상일 때만, 리뷰 3개 이상이 표현한 키워드를 많은 순(같으면 키워드 ID 순)으로 최대 10개 내립니다.
		기준에 못 미치면 keywords는 빈 배열이고 analyzedReviewCount는 그대로 내려갑니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 숨겨졌거나 삭제됨",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ProductReviewKeywordsResponse> getKeywords(
		@Parameter(description = "상품 ID", example = "1") Long productId
	);
}
