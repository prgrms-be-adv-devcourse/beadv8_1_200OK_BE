package com.ok.delivery.controller;

import com.ok.delivery.controller.dto.AddressRequest;
import com.ok.delivery.controller.dto.AddressResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Default Shipping Address", description = "구매자 기본 배송지 API")
interface DefaultShippingAddressControllerSpec {

    @Operation(summary = "내 기본 배송지 조회",
            description = "마이페이지 표시와 주문서 초기값에 쓰는 기본 배송지를 조회한다. 등록하지 않았으면 본문 없이 204를 돌려준다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "204", description = "등록된 기본 배송지 없음"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "일반 회원이 아님")
    })
    ResponseEntity<AddressResponse> getDefaultAddress(Long memberId);

    @Operation(summary = "내 기본 배송지 등록·수정",
            description = "회원당 1개인 기본 배송지를 등록하고, 이미 있으면 바꾼다. 이미 만든 주문과 배송의 배송지는 바뀌지 않는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록·수정 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "일반 회원이 아님"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED)")
    })
    ResponseEntity<AddressResponse> saveDefaultAddress(Long memberId, AddressRequest request);
}
