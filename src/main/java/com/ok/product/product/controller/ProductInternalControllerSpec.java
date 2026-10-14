package com.ok.product.product.controller;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.product.controller.dto.ProductOptionInfoResponse;
import com.ok.product.product.controller.dto.StockAllocationRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

@Tag(name = "Product (Internal)", description = "order 모듈 연동용 내부 API (외부 공개 안 함)")
interface ProductInternalControllerSpec {

	@Operation(summary = "옵션 일괄 조회", description = """
		장바구니 담기·목록, 주문서 작성에서 옵션 정보를 한 번에 조회합니다.
		없는 ID는 응답에서 빠집니다. 숨기거나 삭제한 상품·옵션도 플래그와 함께 응답하고, 구매 가능 여부는 purchasable로 판단합니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공 (옵션 ID 오름차순)"),
		@ApiResponse(
			responseCode = "400", description = "optionIds가 없거나 비었거나 100개 초과, 숫자가 아닌 값",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<List<ProductOptionInfoResponse>> getOptions(
		@Parameter(description = "옵션 ID 목록 (1~100개, 쉼표로 구분)", example = "11,12")
		@Size(min = 1, max = 100) List<Long> optionIds
	);

	@Operation(summary = "재고 배정", description = """
		주문을 저장해 주문 ID를 받은 뒤 호출합니다. 한 트랜잭션으로 처리해 하나라도 실패하면 이 요청의 배정은 모두 취소됩니다.
		같은 주문 ID로 다시 보내면 배정하지 않고 204를 응답합니다.
		409는 type으로 구분합니다: 판매 중이 아니거나 숨김·삭제된 옵션은 option-not-purchasable, 판매 가능 수량 부족은 insufficient-stock.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "배정 성공 또는 이미 배정된 주문"),
		@ApiResponse(
			responseCode = "404", description = "없는 옵션",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "409", description = "판매 중이 아닌 옵션(option-not-purchasable) 또는 재고 부족(insufficient-stock)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "주문 ID 없음, 항목 비어 있음, 수량 1 미만",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	// Spec에 메서드 검증 제약(@Size)이 있어서 구현 쪽에 @Valid를 따로 달면 제약 재정의 오류가 난다. 여기에만 둔다
	ResponseEntity<Void> allocateStock(@Valid StockAllocationRequest request);

	@Operation(summary = "재고 해제", description = """
		주문 취소(배송 전)나 주문 실패 때 그 주문에 배정한 재고를 모두 되돌립니다.
		배정 기록이 없거나 이미 해제했어도 204를 응답하고, 여러 번 호출해도 한 번만 되돌립니다.
		""")
	@ApiResponse(responseCode = "204", description = "해제 성공 또는 해제할 배정 없음")
	ResponseEntity<Void> releaseStock(@Parameter(description = "주문 ID", example = "10") Long orderId);
}
