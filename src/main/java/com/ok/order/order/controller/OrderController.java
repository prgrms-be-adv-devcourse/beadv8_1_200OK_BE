package com.ok.order.order.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.order.order.controller.dto.*;
import com.ok.order.order.service.OrderFacade;
import com.ok.order.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders")
public class OrderController implements OrderControllerSpec{

    private final OrderFacade orderFacade;
    private final OrderService orderService;

    // 주문서 조회 ('선택 상품 주문하기' 누른 뒤 주문서 화면)
    @Override
    @GetMapping("/checkout")
    public ResponseEntity<CheckoutResponse> getCheckout(
            @CurrentMemberId Long memberId
    ) {
        return ResponseEntity.ok((CheckoutResponse.from(orderFacade.getCheckout(memberId))));
    }

    @Override
    @PostMapping
    public ResponseEntity<CreateOrderResponse> createOrder(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        Long orderId = orderFacade.createOrder(request.toCommand(memberId));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateOrderResponse(orderId));
    }

    @Override
    @GetMapping
    public ResponseEntity<OrderSummaryPageResponse> getOrders(
            @CurrentMemberId Long memberId,
            @Valid @ModelAttribute OrderPageRequest request
    ) {
        return ResponseEntity.ok(OrderSummaryPageResponse.from(orderService.getOrders(memberId, request.page(), request.size())));
    }

    @Override
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDetailResponse> getOrder(
            @CurrentMemberId Long memberId,
            @PathVariable Long orderId
    ) {
        return ResponseEntity.ok(OrderDetailResponse.from(orderService.getOrder(memberId, orderId)));
    }
}
