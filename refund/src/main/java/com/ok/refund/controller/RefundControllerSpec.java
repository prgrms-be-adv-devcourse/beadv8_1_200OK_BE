package com.ok.refund.controller;

import com.ok.refund.controller.dto.RefundCreateRequest;
import com.ok.refund.controller.dto.RefundPageRequest;
import com.ok.refund.controller.dto.RefundPageResponse;
import com.ok.refund.controller.dto.RefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;

@Tag(name = "Refund", description = "구매자 환불(출고 전 취소) API")
interface RefundControllerSpec {

    @Operation(summary = "환불 요청",
            description = "출고 전(송장 등록 전) 주문 상품을 개수 단위로 취소 요청한다. 판매자가 승인해야 돈이 돌아온다. "
                    + "한 주문 상품에 진행 중인 요청은 한 건만 받는다. 배송비와 추가 운임은 판매자 승인 때 묶음이 전부 취소될 때만 더해진다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "요청 성공 (REQUESTED)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한(BUYER) 없음"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 주문 상품 (ORDER_ITEM_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "출고 전이 아님 (ORDER_ITEM_NOT_CANCELLABLE), "
                    + "남은 개수보다 많음 (REFUND_QUANTITY_EXCEEDED), 진행 중인 요청 있음 (REFUND_ALREADY_IN_PROGRESS), "
                    + "무료배송 기준이 깨지는데 취소 금액이 배송비보다 작음 (PARTIAL_CANCEL_NOT_ALLOWED)"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED)"),
            @ApiResponse(responseCode = "503", description = "주문 정보를 확인하지 못함 (ORDER_API_FAILED)")
    })
    ResponseEntity<RefundResponse> request(Long buyerId, RefundCreateRequest request);

    @Operation(summary = "내 환불 요청 목록", description = "본인의 환불 요청을 최신순으로 25건씩 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한(BUYER) 없음"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED)")
    })
    ResponseEntity<RefundPageResponse> getMyRefunds(Long buyerId, @ParameterObject RefundPageRequest request);

    @Operation(summary = "환불 요청 철회", description = "판매자가 승인·거절하기 전(REQUESTED)에만 요청을 거둘 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "철회 성공 (WITHDRAWN)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한(BUYER) 없음"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 요청 (REFUND_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "요청됨이 아님 (INVALID_REFUND_STATE)")
    })
    ResponseEntity<RefundResponse> withdraw(
            Long buyerId,
            @Parameter(description = "환불 요청 ID", example = "7") Long refundId
    );
}
