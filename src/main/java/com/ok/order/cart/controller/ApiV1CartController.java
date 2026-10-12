package com.ok.order.cart.controller;

import com.ok.order.cart.controller.dto.AddCartItemRequest;
import com.ok.order.cart.controller.dto.AddCartItemResponse;
import com.ok.order.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carts")
@RequiredArgsConstructor
public class ApiV1CartController {

    private final CartService cartService;

    @PostMapping("/items")
    public ResponseEntity<AddCartItemResponse> addItem(
            @RequestHeader("X-Member-Id") Long memberId, // TODO 인증 적용 후 로그인 회원으로 교체
            @Valid @RequestBody AddCartItemRequest request) {

        Long cartItemId = cartService.addItem(memberId, request.productOptionId(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AddCartItemResponse(cartItemId));
    }
}
