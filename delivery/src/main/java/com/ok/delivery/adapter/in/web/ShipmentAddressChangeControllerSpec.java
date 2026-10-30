package com.ok.delivery.adapter.in.web;

import com.ok.delivery.adapter.in.web.dto.AddressChangeRequestResponse;
import com.ok.delivery.adapter.in.web.dto.AddressRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Shipment Address Change", description = "구매자 배송지 변경 요청 API")
interface ShipmentAddressChangeControllerSpec {

    @Operation(summary = "배송지 변경 요청",
            description = "송장 발행 전(PREPARING)인 본인 shipment의 배송지 변경을 요청한다. 판매자가 승인해야 바뀌고, shipment당 대기 중인 요청은 1건이다. "
                    + "도서산간으로 바뀌어 추가 운임이 늘면 차액을 안내 금액으로 돌려준다. 안내 금액은 결제하지 않고 판매자가 직접 받는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "요청 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "일반 회원이 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 다른 구매자의 shipment (SHIPMENT_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "PREPARING이 아님 (ADDRESS_CHANGE_NOT_ALLOWED), "
                    + "대기 중인 요청이 있음 (ADDRESS_CHANGE_ALREADY_PENDING)"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED)")
    })
    ResponseEntity<AddressChangeRequestResponse> request(
            Long buyerId,
            @Parameter(description = "shipment ID", example = "1") Long shipmentId,
            AddressRequest request
    );
}
