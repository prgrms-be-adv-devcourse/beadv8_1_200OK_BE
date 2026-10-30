package com.ok.delivery.adapter.in.web;

import com.ok.delivery.adapter.in.web.dto.ShipmentDetailResponse;
import com.ok.delivery.adapter.in.web.dto.ShipmentPageRequest;
import com.ok.delivery.adapter.in.web.dto.ShipmentPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;

@Tag(name = "Shipment", description = "구매자 배송 조회 API")
interface ShipmentControllerSpec {

    @Operation(summary = "내 배송 목록",
            description = "본인의 배송을 최신순으로 25건씩 조회한다. 판매자마다 배송이 따로라 한 주문이 여러 건으로 보일 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "일반 회원이 아님"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (VALIDATION_FAILED)")
    })
    ResponseEntity<ShipmentPageResponse> getMyShipments(Long buyerId, @ParameterObject ShipmentPageRequest request);

    @Operation(summary = "내 배송 상세",
            description = "본인 배송의 상태, 송장, 현재 배송지, 배송비, 도착 예정일, 상품 항목, 가장 최근 배송지 변경 요청을 조회한다. "
                    + "additionalShippingFee는 승인된 배송지 변경으로 판매자에게 따로 낼 추가 운임 합계이고, 없으면 0이다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "일반 회원이 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 다른 구매자의 shipment (SHIPMENT_NOT_FOUND)")
    })
    ResponseEntity<ShipmentDetailResponse> getMyShipment(
            Long buyerId,
            @Parameter(description = "shipment ID", example = "1") Long shipmentId
    );
}
