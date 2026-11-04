package com.ok.refund.controller;

import com.ok.refund.controller.dto.RefundCreateRequest;
import com.ok.refund.controller.dto.RefundPageRequest;
import com.ok.refund.controller.dto.RefundPageResponse;
import com.ok.refund.controller.dto.RefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;

@Tag(name = "Refund", description = "구매자 환불(출고 전 취소) API")
interface RefundControllerSpec {

    @Operation(summary = "환불 요청",
            description = "출고 전 주문 상품을 개수 단위로 취소 요청한다. 판매자가 승인해야 돈이 돌아오고, 요청만으로는 주문·재고·배송이 바뀌지 않는다. "
                    + "한 주문 상품에 진행 중인 요청은 한 건만 받는다. 취소 사유는 선택이며, 공백만 적으면 적지 않은 것으로 본다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "요청 성공 (REQUESTED)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한(BUYER) 없음"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 주문 상품 (ORDER_ITEM_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "출고 전이 아님 (ORDER_ITEM_NOT_CANCELLABLE), "
                    + "산 개수보다 많음 (REFUND_QUANTITY_EXCEEDED), 진행 중인 요청 있음 (REFUND_ALREADY_IN_PROGRESS)"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED). 개수 1 미만, 취소 사유 255자 초과 등"),
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

    @Operation(summary = "환불 요청 철회",
            description = "판매자가 승인하기 전(요청됨)에 본인 요청을 거둔다. 철회하면 같은 상품에 다시 요청할 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "철회 성공 (WITHDRAWN)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한(BUYER) 없음"),
            @ApiResponse(responseCode = "404", description = "없거나 남의 요청 (REFUND_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "요청됨이 아님. 이미 승인·거절됐거나 철회함 (INVALID_REFUND_STATE)")
    })
    ResponseEntity<RefundResponse> withdraw(Long buyerId, Long refundId);
}
