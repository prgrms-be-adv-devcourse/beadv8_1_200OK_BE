package com.ok.product.review.controller;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.product.controller.dto.ProductPageResponse;
import com.ok.product.review.controller.dto.ProductReviewCreateRequest;
import com.ok.product.review.controller.dto.ProductReviewCreateResponse;
import com.ok.product.review.controller.dto.ProductReviewResponse;
import com.ok.product.review.controller.dto.ProductReviewUpdateRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// 검증 어노테이션은 여기에만 둔다. 구현 클래스에 다시 쓰면 HV000151로 거부된다
@Tag(name = "Product Review", description = "상품 리뷰 API")
interface ProductReviewControllerSpec {

	@Operation(summary = "리뷰 작성", description = """
		구매한 상품의 리뷰를 작성합니다. 별점은 0.5점 단위를 정수 1~10으로 보냅니다(7 = 3.5점).
		한 주문에서 같은 상품은 옵션을 여러 개 샀어도 리뷰 1개이고, 다른 주문(재구매)이면 다시 쓸 수 있습니다.
		리뷰를 삭제해도 같은 주문의 같은 상품으로는 다시 쓸 수 없습니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "작성 성공"),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됨, 그 상품의 옵션이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "409", description = "이 주문에서 이미 리뷰를 작성한 상품",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class),
				examples = @ExampleObject(value = """
					{
					  "type": "product/duplicate-review",
					  "title": "Duplicate review",
					  "status": 409,
					  "detail": "이 주문에서 이미 리뷰를 작성한 상품입니다."
					}
					"""))
		),
		@ApiResponse(
			responseCode = "422", description = "요청 값 검증 실패, 업로드하지 않은 첨부 URL, 영상 2개 이상",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ProductReviewCreateResponse> create(
		Long memberId,
		@Parameter(description = "상품 ID", example = "10") Long productId,
		@Valid ProductReviewCreateRequest request
	);

	@Operation(summary = "리뷰 수정", description = "내 리뷰의 별점, 내용, 첨부를 수정합니다. 첨부는 보낸 목록으로 전체 교체합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "수정 성공"),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "403", description = "내 리뷰가 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "리뷰가 없거나 삭제됨",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "요청 값 검증 실패, 업로드하지 않은 첨부 URL, 영상 2개 이상",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<Void> update(
		Long memberId,
		@Parameter(description = "리뷰 ID", example = "1") Long reviewId,
		@Valid ProductReviewUpdateRequest request
	);

	@Operation(summary = "리뷰 삭제")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "삭제 성공"),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "403", description = "내 리뷰가 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "리뷰가 없거나 이미 삭제됨",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<Void> delete(
		Long memberId,
		@Parameter(description = "리뷰 ID", example = "1") Long reviewId
	);

	@SecurityRequirements
	@Operation(summary = "상품별 리뷰 목록", description = "노출된 상품의 리뷰를 최신순으로 조회합니다. 로그인하지 않아도 됩니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "400", description = "범위를 벗어난 page·size",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됐거나 숨김",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ProductPageResponse<ProductReviewResponse>> getProductReviews(
		@Parameter(description = "상품 ID", example = "10") Long productId,
		@Parameter(description = "페이지 번호 (0부터)", example = "0") @Min(0) int page,
		@Parameter(description = "페이지 크기 (1~100)", example = "20") @Min(1) @Max(100) int size
	);

	@Operation(summary = "이 상품의 내 리뷰", description = """
		상품 상세 상단에 고정해 보여 줄 내 리뷰를 최신순으로 조회합니다. 재구매하면 주문마다 쓸 수 있어 여러 건일 수 있습니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공 (없으면 빈 목록)"),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<List<ProductReviewResponse>> getMyReviewsOfProduct(
		Long memberId,
		@Parameter(description = "상품 ID", example = "10") Long productId
	);

	@Operation(summary = "내가 쓴 리뷰 목록", description = "내가 쓴 리뷰를 최신순으로 조회합니다. 상품이 숨겨지거나 삭제돼도 나옵니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "400", description = "범위를 벗어난 page·size",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ProductPageResponse<ProductReviewResponse>> getMyReviews(
		Long memberId,
		@Parameter(description = "페이지 번호 (0부터)", example = "0") @Min(0) int page,
		@Parameter(description = "페이지 크기 (1~100)", example = "20") @Min(1) @Max(100) int size
	);
}
