package com.ok.delivery.controller;

import com.ok.delivery.controller.dto.ShipmentDispatchRequest;
import com.ok.delivery.controller.dto.ShipmentDispatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Seller Shipment", description = "판매자 출고 API")
interface SellerShipmentControllerSpec {

    @Operation(summary = "출고 및 송장 등록",
            description = "택배사와 송장번호를 등록해 PREPARING에서 SHIPPING으로 바꾼다. 송장 등록 후에는 취소와 배송지 변경을 할 수 없고, "
                    + "대기 중인 배송지 변경 요청은 고정 문구로 자동 거절된다. 송장번호는 같은 택배사 안에서 중복될 수 없다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "출고 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "판매자가 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 다른 판매자의 shipment (SHIPMENT_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "PREPARING이 아님 (INVALID_SHIPMENT_STATE), "
                    + "이미 사용된 송장번호 (TRACKING_NUMBER_DUPLICATED), "
                    + "환불이 취소 확정을 예약한 항목이 있음 (CANCELLATION_RESERVED)"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED), "
                    + "송장번호가 숫자 10~13자리가 아님 (INVALID_TRACKING_NUMBER)")
    })
    ResponseEntity<ShipmentDispatchResponse> dispatch(
            Long sellerId,
            @Parameter(description = "shipment ID", example = "1") Long shipmentId,
            ShipmentDispatchRequest request
    );
}
