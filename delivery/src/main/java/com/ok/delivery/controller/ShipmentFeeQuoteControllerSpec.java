package com.ok.delivery.controller;

import com.ok.delivery.controller.dto.ShippingFeeQuoteRequest;
import com.ok.delivery.controller.dto.ShippingFeeQuoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Shipment Fee Quote", description = "배송비 견적 API")
interface ShipmentFeeQuoteControllerSpec {

    @Operation(summary = "배송비·도착 예정일 견적", description = """
            주문 전에 배송지와 판매자별 상품 금액으로 배송비와 출고·도착 예정일을 미리 보여 줍니다.
            배송(송장)이 판매자 단위라서 무료배송 기준과 도서산간 추가 운임을 판매자마다 따로 계산합니다.
            예정일은 요청 시각 기준이라 실제 결제 시각에 따라 달라질 수 있습니다.
            표시용 값이며 결제 금액은 주문이 서버에서 다시 계산합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "견적 성공"),
            @ApiResponse(responseCode = "422", description = "우편번호 형식 오류, 판매자 목록이 비었거나 100개 초과, 판매자 ID 없음, 상품 금액 음수, 판매자 중복")
    })
    ResponseEntity<ShippingFeeQuoteResponse> quote(ShippingFeeQuoteRequest request);
}
