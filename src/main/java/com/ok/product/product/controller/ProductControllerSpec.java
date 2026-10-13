package com.ok.product.product.controller;

import com.ok.member.MemberRole;
import com.ok.product.product.controller.dto.ProductCreateRequest;
import com.ok.product.product.controller.dto.ProductCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

@Tag(name = "Product", description = "상품 API")
interface ProductControllerSpec {

	@Operation(summary = "상품 등록")
	@ApiResponses({
		@ApiResponse(
				responseCode = "201", description = "등록 성공",
				content = @Content(schema = @Schema(implementation = ProductCreateResponse.class))
		),
		@ApiResponse(
			responseCode = "403", description = "상품을 등록할 수 없는 권한 (SELLER가 아님)",
			content = @Content(mediaType = "application/problem+json",
			schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "요청 값 검증 실패, 판매가가 정가보다 큼, 업로드하지 않은 이미지 URL 등",
			content = @Content(schema = @Schema(implementation = ProblemDetail.class), mediaType = "application/problem+json")
		)
	})
	// TODO: memberId, memberRole 회원 기능 완료 후 다시 체크 필요 현재 member 모듈 의존중
	ResponseEntity<ProductCreateResponse> create(
		@Parameter(description = "요청 회원 ID (임시)", example = "1") Long memberId,
		@Parameter(description = "요청 회원 권한 (임시)", example = "SELLER") MemberRole memberRole,
		ProductCreateRequest request
	);
}
