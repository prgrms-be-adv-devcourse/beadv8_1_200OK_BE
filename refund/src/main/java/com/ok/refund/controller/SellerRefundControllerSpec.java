package com.ok.refund.controller;

import com.ok.refund.controller.dto.RefundDecisionRequest;
import com.ok.refund.controller.dto.RefundPageResponse;
import com.ok.refund.controller.dto.RefundResponse;
import com.ok.refund.controller.dto.SellerRefundPageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED)")
    })
    ResponseEntity<RefundPageResponse> getMyRefunds(Long sellerId, @ParameterObject SellerRefundPageRequest request);

    @Operation(summary = "환불 요청 승인·거절",
            description = "요청됨(REQUESTED)인 요청을 승인하거나 거절한다. 승인하면 배송 잠금 → 예치금 반환 → 주문 반영 → 재고 복구 → 잠금 해제 순서로 처리해 "
                    + "COMPLETED로 응답한다. 이미 출고되어 배송 잠금이 거절되면 NOT_APPROVABLE로 응답한다(상태가 저장되는 정상 결과라 200). "
                    + "승인 뒤 중간 단계가 실패하면 PROCESSING으로 남고 503으로 응답한다. 승인 뒤에는 되돌리거나 거절할 수 없다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 결과 (COMPLETED, NOT_APPROVABLE, REJECTED)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "판매자가 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 다른 판매자의 요청 (REFUND_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "요청됨이 아님 (INVALID_REFUND_STATE), "
                    + "배송 준비가 아직 안 끝나 잠시 후 다시 (SHIPMENT_NOT_READY), 주문 수량을 넘음 (REFUND_QUANTITY_EXCEEDED)"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED), 거절인데 사유 없음 (REJECT_REASON_REQUIRED)"),
            @ApiResponse(responseCode = "503", description = "다른 모듈 호출 실패 (ORDER_API_FAILED, DELIVERY_API_FAILED, "
                    + "WALLET_API_FAILED, PRODUCT_API_FAILED). 승인 뒤 실패면 PROCESSING으로 남는다")
    })
    ResponseEntity<RefundResponse> decide(
            Long sellerId,
            @Parameter(description = "환불 요청 ID", example = "7") Long refundId,
            RefundDecisionRequest request
    );
}
