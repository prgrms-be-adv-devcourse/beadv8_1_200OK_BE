package com.ok.product.product.controller;

import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.product.controller.dto.ProductOptionInfoResponse;
import com.ok.product.product.controller.dto.StockAllocationRequest;
import com.ok.product.product.controller.dto.StockPartialReleaseRequest;

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
		같은 주문 ID로 다시 보내면 items는 비교하지 않고 첫 배정을 유지한 채 204를 응답합니다. 수량을 바꾸려면 새 주문 ID로 요청합니다.
		이미 해제한 주문 ID는 다시 쓸 수 없어 409를 응답합니다.
		409는 type으로 구분합니다: 판매 중이 아니거나 숨김·삭제된 옵션은 option-not-purchasable, 판매 가능 수량 부족은 insufficient-stock, 해제한 주문은 allocation-already-released,
		같은 주문 ID 요청이 동시에 겹치면 allocation-in-progress(실패가 아니므로 다시 요청하면 됩니다).
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "배정 성공 또는 이미 배정된 주문"),
		@ApiResponse(
			responseCode = "404", description = "없는 옵션",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "409", description = "판매 중이 아닌 옵션(option-not-purchasable), 재고 부족(insufficient-stock), 해제한 주문(allocation-already-released), 같은 주문 동시 요청(allocation-in-progress)",
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

	@Operation(summary = "재고 일부 해제", description = """
		환불 1단계(출고 전 취소)에서 주문의 한 옵션 배정 재고를 환불 수량만큼 되돌립니다. 출고 후 반품에는 쓰지 않습니다.
		출고해도 배정 수량이 그대로라, 출고 후에 풀면 실물 없이 판매 가능 수량이 늘어나기 때문입니다. 상품 모듈은 출고 여부를 모르므로 이 전제는 환불 흐름이 지킵니다.
		같은 refundId로 다시 보내면 같은 내용일 때 204, 주문·옵션·수량 중 하나라도 다르면 409(partial-release-mismatch)를 응답합니다.
		내용 불일치는 수량 초과보다 먼저 판단합니다. 같은 refundId 이력이 있으면 남은 수량과 관계없이 partial-release-mismatch입니다.
		수량이 0이 돼도 배정 기록은 남고, 이후 주문 전체 해제(release)는 남은 수량만 되돌립니다.
		같은 refundId 요청이 동시에 겹쳐도 먼저 처리된 요청과 같은 내용이면 204, 다르면 409(partial-release-mismatch)를 응답합니다.
		409는 type으로 구분합니다: 해제 가능 수량 초과 또는 이미 전체 해제한 주문은 release-quantity-exceeded, 같은 refundId인데 내용이 다르면 partial-release-mismatch,
		동시에 겹친 같은 refundId의 이력이 아직 보이지 않는 드문 경우는 partial-release-in-progress(실패가 아니므로 다시 요청하면 됩니다).
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "해제 성공 또는 같은 환불로 이미 해제함"),
		@ApiResponse(
			responseCode = "404", description = "주문에 그 옵션의 배정 기록 없음(allocation-not-found)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "409", description = "해제 가능 수량 초과(release-quantity-exceeded), 같은 환불 다른 내용(partial-release-mismatch), 같은 환불 동시 요청의 이력 확인 전(partial-release-in-progress)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "422", description = "환불 ID·옵션 ID·수량 없음, 수량 1 미만",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<Void> partialReleaseStock(
		@Parameter(description = "주문 ID", example = "10") Long orderId,
		@Valid StockPartialReleaseRequest request
	);
}
