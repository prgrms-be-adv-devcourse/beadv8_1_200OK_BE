package com.ok.refund.controller;

import com.ok.refund.controller.dto.RefundPageResponse;
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
}
