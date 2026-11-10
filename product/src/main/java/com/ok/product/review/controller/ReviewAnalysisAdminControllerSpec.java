package com.ok.product.review.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.review.controller.dto.ReviewAnalysisPreviewRequest;
import com.ok.product.review.controller.dto.ReviewKeywordPreviewResponse;
import com.ok.product.review.controller.dto.ReviewSentimentPreviewResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Review Analysis (Admin)", description = "관리자 리뷰 분석 테스트 API")
interface ReviewAnalysisAdminControllerSpec {

	@Operation(summary = "리뷰 본문 키워드 분석 테스트", description = """
		리뷰를 남기지 않고 본문 하나를 운영 작업자와 같은 입력(상품명, 상품 분류)과 같은 판정으로 키워드 분석합니다. 결과는 저장하지 않습니다.
		상품 분류에 묻는 키워드 전부를 확률과 판정과 함께 돌려줍니다. 키워드 선택지 순서는 본문으로 정해, 같은 본문은 같은 순서로 묻습니다.
		내용 없는 본문은 분석기를 부르지 않고 analyzed=false로 돌려줍니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "400", description = "옵션이 그 상품의 것이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "403", description = "ADMIN이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됨",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "요청 값 검증 실패 (본문이 비었거나 2,000자 초과 등)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "502", description = "분석기가 결과를 돌려주지 않음 (오류, 시간 초과, 해석할 수 없는 응답). 다시 시도하지 않는다",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "503", description = "분석기가 인증을 거절함 (키 설정 오류)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ReviewKeywordPreviewResponse> analyzeKeywords(ReviewAnalysisPreviewRequest request);

	@Operation(summary = "리뷰 본문 감성 분석 테스트", description = """
		리뷰를 남기지 않고 본문 하나를 운영 작업자와 같은 입력(상품명, 옵션명)으로 감성 분석합니다. 결과는 저장하지 않습니다.
		선택지 순서는 본문으로 정해, 같은 본문은 같은 순서로 묻습니다.
		내용 없는 본문은 분석기를 부르지 않고 analyzed=false로 돌려줍니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "400", description = "옵션이 그 상품의 것이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "403", description = "ADMIN이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됨",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "요청 값 검증 실패 (본문이 비었거나 2,000자 초과 등)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "502", description = "분석기가 결과를 돌려주지 않음 (오류, 시간 초과, 해석할 수 없는 응답). 다시 시도하지 않는다",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "503", description = "분석기가 인증을 거절함 (키 설정 오류)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ReviewSentimentPreviewResponse> analyzeSentiment(ReviewAnalysisPreviewRequest request);
}
