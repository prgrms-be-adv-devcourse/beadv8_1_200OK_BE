package com.ok.product.product.controller;

import com.ok.member.MemberRole;
import com.ok.product.product.controller.dto.ProductCreateRequest;
import com.ok.product.product.controller.dto.ProductCreateResponse;
import com.ok.product.product.controller.dto.ProductStatusChangeRequest;
import com.ok.product.product.controller.dto.ProductUpdateRequest;
import com.ok.product.product.controller.dto.ProductVisibilityChangeRequest;
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

	@Operation(summary = "상품 수정", description = """
		상품 정보, 판매 상태·노출, 이미지, 옵션을 한 번에 수정합니다. 판매 상태·노출은 생략하면 그대로 둡니다.
		옵션은 id가 있으면 수정, 없으면 추가, 요청에 없는 기존 옵션은 삭제됩니다.
		수정 후 최신 version은 상품 상세 조회로 다시 받습니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "수정 성공"),
		@ApiResponse(
			responseCode = "403", description = "SELLER가 아니거나 본인 상품이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됨, 요청한 옵션 id가 이 상품의 옵션이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "409", description = "옵션명 중복, 다른 곳에서 먼저 수정함 (version 불일치)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "요청 값 검증 실패, 판매가가 정가보다 큼, 총재고가 배정 수량보다 작음 등",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<Void> update(
		@Parameter(description = "요청 회원 ID (임시)", example = "1") Long memberId,
		@Parameter(description = "요청 회원 권한 (임시)", example = "SELLER") MemberRole memberRole,
		@Parameter(description = "상품 ID", example = "1") Long productId,
		ProductUpdateRequest request
	);

	@Operation(summary = "상품 판매 상태 변경", description = """
		판매 상태(READY, ON_SALE, PAUSED, ENDED)만 바꿉니다. 판매자 상품 목록에서 바로 바꾸는 용도입니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "성공"),
		@ApiResponse(
			responseCode = "403", description = "SELLER가 아니거나 본인 상품이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됨",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "409", description = "다른 요청이 먼저 상품을 바꿈",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "판매 상태가 없음 (없는 값을 보내면 400)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<Void> changeStatus(
		@Parameter(description = "요청 회원 ID (임시)", example = "1") Long memberId,
		@Parameter(description = "요청 회원 권한 (임시)", example = "SELLER") MemberRole memberRole,
		@Parameter(description = "상품 ID", example = "1") Long productId,
		ProductStatusChangeRequest request
	);

	@Operation(summary = "상품 노출 변경", description = """
		노출 여부(VISIBLE, HIDDEN)만 바꿉니다. 판매자 상품 목록에서 바로 바꾸는 용도입니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "성공"),
		@ApiResponse(
			responseCode = "403", description = "SELLER가 아니거나 본인 상품이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됨",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "409", description = "다른 요청이 먼저 상품을 바꿈",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "노출 여부가 없음 (없는 값을 보내면 400)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<Void> changeVisibility(
		@Parameter(description = "요청 회원 ID (임시)", example = "1") Long memberId,
		@Parameter(description = "요청 회원 권한 (임시)", example = "SELLER") MemberRole memberRole,
		@Parameter(description = "상품 ID", example = "1") Long productId,
		ProductVisibilityChangeRequest request
	);

	@Operation(summary = "상품 삭제", description = """
		상품과 옵션을 삭제 표시합니다. 되돌릴 수 없고, 삭제 후에는 조회·수정·주문이 모두 불가합니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "성공"),
		@ApiResponse(
			responseCode = "403", description = "SELLER가 아니거나 본인 상품이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "404", description = "상품이 없거나 삭제됨",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "409", description = "다른 요청이 먼저 상품을 바꿈",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<Void> delete(
		@Parameter(description = "요청 회원 ID (임시)", example = "1") Long memberId,
		@Parameter(description = "요청 회원 권한 (임시)", example = "SELLER") MemberRole memberRole,
		@Parameter(description = "상품 ID", example = "1") Long productId
	);
}
