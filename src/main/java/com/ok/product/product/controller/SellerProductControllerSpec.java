package com.ok.product.product.controller;

import com.ok.product.product.controller.dto.ProductPageResponse;
import com.ok.product.product.controller.dto.SellerProductSummaryResponse;
import com.ok.product.product.service.dto.ProductStatusCode;
import com.ok.product.product.service.dto.ProductVisibilityCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

@Tag(name = "Product (Seller)", description = "판매자 상품 관리 API")
interface SellerProductControllerSpec {

	@Operation(summary = "내 상품 목록 조회", description = """
		로그인한 판매자의 상품을 최신순으로 조회합니다. 관리 화면용이라 숨긴(HIDDEN) 상품도 나오고, 삭제한 상품만 빠집니다.
		필터는 모두 선택이고, 함께 보내면 모두 만족하는 상품만 나옵니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "400", description = "없는 판매 상태·노출 값, 범위를 벗어난 page·size",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "403", description = "SELLER가 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ProductPageResponse<SellerProductSummaryResponse>> search(
		Long memberId,
		@Parameter(description = "판매 상태", example = "ON_SALE") ProductStatusCode status,
		@Parameter(description = "노출 여부", example = "VISIBLE") ProductVisibilityCode visibility,
		@Parameter(description = "페이지 번호 (0부터)", example = "0") @Min(0) int page,
		@Parameter(description = "페이지 크기 (1~100)", example = "20") @Min(1) @Max(100) int size
	);
}
