package com.ok.refund.controller;

import com.ok.refund.controller.dto.RefundDecisionRequest;
import com.ok.refund.controller.dto.RefundPageResponse;
import com.ok.refund.controller.dto.RefundResponse;
import com.ok.refund.controller.dto.SellerRefundPageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;

@Tag(name = "Seller Refund", description = "판매자 환불(출고 전 취소) 처리 API")
interface SellerRefundControllerSpec {

    @Operation(summary = "환불 요청 목록", description = "본인 상품에 온 환불 요청을 최신순으로 25건씩 조회한다. status로 상태를 거를 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "판매자가 아님"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED). 페이지 음수, 없는 상태 등")
    })
    ResponseEntity<RefundPageResponse> getMyRefunds(Long sellerId, @ParameterObject SellerRefundPageRequest request);

    @Operation(summary = "환불 요청 승인·거절",
            description = "본인 상품에 온 \"요청됨\" 환불을 승인하거나 거절한다. 승인하면 PROCESSING(처리 중)이 되고 되돌릴 수 없다. "
                    + "거절하면 REJECTED가 되고, 구매자는 같은 상품에 다시 요청할 수 있다. 거절 사유는 필수다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공 (PROCESSING 또는 REJECTED)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "판매자가 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 상품 요청 (REFUND_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "요청됨이 아님. 이미 승인·거절됐거나 구매자가 철회함 (INVALID_REFUND_STATE)"),
            @ApiResponse(responseCode = "422", description = "거절 사유 없음 (REJECT_REASON_REQUIRED), "
                    + "요청 값 검증 실패 (VALIDATION_FAILED). 결정 값 오류, 사유 255자 초과 등")
    })
    ResponseEntity<RefundResponse> decide(Long sellerId, Long refundId, RefundDecisionRequest request);
}
