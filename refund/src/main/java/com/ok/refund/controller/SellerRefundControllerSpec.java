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
                    + "승인할 때 주문을 다시 조회해 같은 판매자 묶음의 배송비를 정하고 응답 금액 칸에 담는다. "
                    + "승인했어도 그사이 같은 묶음의 다른 요청이 먼저 승인돼 취소 금액이 뺄 배송비보다 적어졌으면 REJECTED(자동 거절)로 응답하고, "
                    + "거절 사유에 그 이유를 넣는다. "
                    + "거절하면 REJECTED가 되고, 구매자는 같은 상품에 다시 요청할 수 있다. 거절 사유는 필수다. 거절은 주문을 조회하지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공 (PROCESSING 또는 REJECTED). 승인 요청이어도 자동 거절이면 REJECTED. "
                    + "승인 때 이미 출고됐거나 취소된 상품이면 \"이미 출고됐거나 취소된 상품이라 취소할 수 없습니다.\" 사유로 자동 거절한다"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "판매자가 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 상품 요청 (REFUND_NOT_FOUND), "
                    + "승인 때 주문에 그 상품이 없음 (ORDER_ITEM_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "요청됨이 아님. 이미 승인·거절됐거나 구매자가 철회함 (INVALID_REFUND_STATE). "
                    + "요청됨이 아니면서 출고도 됐으면 INVALID_REFUND_STATE다"),
            @ApiResponse(responseCode = "422", description = "거절 사유 없음 (REJECT_REASON_REQUIRED). 사유를 상태보다 먼저 보므로 "
                    + "이미 처리된 요청이어도 사유 없이 거절하면 409가 아니라 이 응답이다. "
                    + "요청 값 검증 실패 (VALIDATION_FAILED). 결정 값 오류, 사유 255자 초과 등"),
            @ApiResponse(responseCode = "503", description = "승인 때 주문 정보를 확인하지 못함 (ORDER_API_FAILED). 요청됨 그대로 남는다")
    })
    ResponseEntity<RefundResponse> decide(Long sellerId, Long refundId, RefundDecisionRequest request);
}
