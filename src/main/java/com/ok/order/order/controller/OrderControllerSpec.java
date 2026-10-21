package com.ok.order.order.controller;

import com.ok.order.order.controller.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Order", description = "주문 API")
interface OrderControllerSpec {

    @Operation(summary = "주문서 조회", description = "장바구니에서 선택한 상품으로 판매자별 상품, 배송비, 합계를 계산합니다. 저장하지 않습니다. 보유 잔액은 지갑 API로 프론트가 따로 조회합니다.")
    ResponseEntity<CheckoutResponse> getCheckout(Long memberId);

    @Operation(summary = "주문 생성", description = "배송지·동의·확인 금액을 받아 주문을 생성하고 재고를 선점합니다.")
    ResponseEntity<CreateOrderResponse> createOrder(Long memberId, CreateOrderRequest request);

    @Operation(summary = "내 주문 목록", description = "로그인한 구매자의 주문을 최신순으로 페이지 조회합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "422", description = "페이지 번호·크기 검증 실패")
    ResponseEntity<OrderSummaryPageResponse> getOrders(Long memberId, OrderPageRequest request);

    @Operation(summary = "내 주문 상세", description = "판매자별 묶음, 상품별 상태, 배송지, 결제 금액을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "주문이 없거나 다른 회원의 주문")
    ResponseEntity<OrderDetailResponse> getOrder(Long memberId, Long orderId);

    @Operation(summary = "구매확정", description = "배송 완료된 내 주문 상품을 구매확정합니다. 확정되면 정산 대상이 됩니다. 배송 완료 3일 후에는 자동으로 확정됩니다.")
    @ApiResponse(responseCode = "204", description = "구매확정 성공")
    @ApiResponse(responseCode = "404", description = "주문 또는 주문 상품이 없거나 다른 회원의 주문")
    @ApiResponse(responseCode = "409", description = "배송 완료 상태가 아니거나(환불 중 포함) 이미 구매확정된 상품")
    ResponseEntity<Void> confirmPurchase(Long memberId, Long orderId, Long orderItemId);
}
