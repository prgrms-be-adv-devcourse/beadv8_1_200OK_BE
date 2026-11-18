package com.ok.product.product.adapter.in.web;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.product.adapter.in.web.dto.BuyerProductPageResponse;
import com.ok.product.product.adapter.in.web.dto.ProductCategoryGroupResponse;
import com.ok.product.product.adapter.in.web.dto.ProductDetailResponse;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ProductCategoryGroupCode;
import com.ok.product.product.application.dto.ProductRegionCode;
import com.ok.product.product.application.dto.ProductSortCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Tag(name = "Product (Buyer)", description = "구매자 상품 조회 API")
interface BuyerProductControllerSpec {

	@SecurityRequirements
	@Operation(summary = "상품 목록 조회", description = """
		노출(VISIBLE)된 상품을 조회합니다. 판매 상태와 관계없이 나오므로 화면은 status로 판매 예정·일시 중지·판매 종료를 표시합니다.
		keyword가 있으면 상품명·옵션명·카테고리 이름으로 찾습니다.
		공백·기호를 빼고 한 글자인 keyword(예: 귤)는 그 글자로 찾도록 정해 둔 품목의 상품만 찾습니다. 귤은 감귤 상품도 찾고, 배는 배추·배즙 상품을 찾지 않습니다.
		검색어와 관계가 낮은 상품은 정렬과 관계없이 결과와 totalElements에서 빠지고, 맞는 상품이 없으면 빈 목록입니다.
		sort를 보내지 않으면 keyword가 있을 때 정확도순, 없을 때 최신순입니다. keyword 없이 RELEVANCE를 보내면 최신순으로 나옵니다.
		어떤 정렬이든 품절 상품(soldOut)은 판매 가능한 상품 뒤에 옵니다. 품절 상품도 목록에서 빠지지는 않습니다.
		검색어와 필터는 모두 선택이고, 함께 보내면 모두 만족하는 상품만 나옵니다.
		keyword가 품목 이름과 자모 하나만 다르면(예: 고추가루) suggestion에 바른 품목(예: 고춧가루)을 담습니다. 결과는 원래 keyword로 찾은 그대로입니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공"),
		@ApiResponse(
			responseCode = "400", description = "50자를 넘는 keyword, 없는 카테고리·산지·정렬 값, 범위를 벗어난 page·size",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "503", description = "조회가 시간 제한을 넘음(product-search-timeout). 잠시 뒤 다시 시도합니다",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<BuyerProductPageResponse> search(
		@Parameter(description = "검색어 (50자 이하, 앞뒤 공백 포함). 띄어쓰기가 달라도 찾습니다. 비우거나 공백·기호만 보내면 검색어 없이 조회합니다",
			example = "샤인머스캣") @Size(max = 50) String keyword,
		@Parameter(description = "상위 카테고리", example = "AGRICULTURE") ProductCategoryGroupCode categoryGroup,
		@Parameter(description = "하위 카테고리", example = "FRUIT") ProductCategoryCode category,
		@Parameter(description = "산지 (시·도)", example = "GYEONGBUK") ProductRegionCode region,
		@Parameter(description = """
			정렬. RELEVANCE(정확도순), PRICE_ASC(낮은 가격순), PRICE_DESC(높은 가격순), LATEST(최신순).
			가격은 minSalePrice 기준입니다. 정확도·가격이 같으면 리뷰 수로 보정한 평점이 높은 순, 그다음 최신순입니다""",
			example = "PRICE_ASC") ProductSortCode sort,
		@Parameter(description = "페이지 번호 (0부터)", example = "0") @Min(0) int page,
		@Parameter(description = "페이지 크기 (1~100)", example = "20") @Min(1) @Max(100) int size
	);

	@SecurityRequirements
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

	@SecurityRequirements
	@Operation(summary = "카테고리 목록 조회", description = "목록 조회 필터에 쓰는 상위·하위 카테고리 코드와 이름을 내려줍니다.")
	@ApiResponse(responseCode = "200", description = "성공")
	ResponseEntity<List<ProductCategoryGroupResponse>> getCategories();
}
