package com.ok.product.product.controller;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.product.controller.dto.ProductCategoryGroupResponse;
import com.ok.product.product.controller.dto.ProductDetailResponse;
import com.ok.product.product.controller.dto.ProductPageResponse;
import com.ok.product.product.controller.dto.ProductSummaryResponse;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductRegion;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Tag(name = "Product (Buyer)", description = "구매자 상품 조회 API")
interface BuyerProductControllerSpec {

	@Operation(summary = "상품 목록 조회", description = """
		노출(VISIBLE)된 상품을 최신순으로 조회합니다. 판매 상태와 관계없이 나오므로 화면은 status로 판매 예정·일시 중지·판매 종료를 표시합니다.
		필터는 모두 선택이고, 함께 보내면 모두 만족하는 상품만 나옵니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "400", description = "없는 카테고리·산지 값, 범위를 벗어난 page·size",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ProductPageResponse<ProductSummaryResponse>> search(
		@Parameter(description = "상위 카테고리", example = "AGRICULTURE") ProductCategoryGroup categoryGroup,
		@Parameter(description = "하위 카테고리", example = "FRUIT") ProductCategory category,
		@Parameter(description = "산지 (시·도)", example = "GYEONGBUK") ProductRegion region,
		@Parameter(description = "페이지 번호 (0부터)", example = "0") @Min(0) int page,
		@Parameter(description = "페이지 크기 (1~100)", example = "20") @Min(1) @Max(100) int size
	);

	@Operation(summary = "상품 상세 조회", description = """
		노출(VISIBLE)된 상품의 상세를 조회합니다. 숨긴 상품과 삭제된 상품은 404입니다.
		옵션은 재고 수량 대신 품절 여부만 내려줍니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됐거나 숨김",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<ProductDetailResponse> getProduct(
		@Parameter(description = "상품 ID", example = "1") Long productId
	);

	@Operation(summary = "카테고리 목록 조회", description = "목록 조회 필터에 쓰는 상위·하위 카테고리 코드와 이름을 내려줍니다.")
	@ApiResponse(responseCode = "200", description = "성공")
	ResponseEntity<List<ProductCategoryGroupResponse>> getCategories();
}
