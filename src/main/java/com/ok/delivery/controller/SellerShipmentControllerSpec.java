package com.ok.delivery.controller;

import com.ok.delivery.controller.dto.AddressChangeDecisionRequest;
import com.ok.delivery.controller.dto.AddressChangeRequestResponse;
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

    @Operation(summary = "배송지 변경 요청 승인·거절",
            description = "대기 중인 배송지 변경 요청을 승인하거나 거절한다. 승인하면 shipment의 배송지, 지역 그룹, 도착 예정일이 요청 배송지 기준으로 바뀌고 "
                    + "주문의 확정 배송지는 바뀌지 않는다. 도착 예정일은 승인 시각 기준으로 다시 계산한다. "
                    + "거절하면 고정 사유 \"상품 준비가 완료되어 배송지 변경이 불가능합니다.\"가 남는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "판매자가 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 다른 판매자의 shipment (SHIPMENT_NOT_FOUND), "
                    + "이 shipment의 요청이 아님 (ADDRESS_CHANGE_REQUEST_NOT_FOUND)"),
            @ApiResponse(responseCode = "409", description = "이미 처리된 요청 (ADDRESS_CHANGE_ALREADY_PROCESSED), "
                    + "승인할 때 PREPARING이 아님 (INVALID_SHIPMENT_STATE)"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED)")
    })
    ResponseEntity<AddressChangeRequestResponse> decideAddressChange(
            Long sellerId,
            @Parameter(description = "shipment ID", example = "1") Long shipmentId,
            @Parameter(description = "배송지 변경 요청 ID", example = "1") Long requestId,
            AddressChangeDecisionRequest request
    );
}
