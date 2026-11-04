package com.ok.order.cart.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.order.cart.controller.dto.*;
import com.ok.order.cart.service.CartService;
import com.ok.order.cart.service.dto.AddCartItemResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
// 장바구니는 구매를 위한 것이라 주문과 같이 구매 권한(BUYER)이 있는 회원만 쓴다
@PreAuthorize("hasRole('BUYER')")
@RequiredArgsConstructor
public class ApiV1CartController implements CartControllerDocs {

    private final CartService cartService;

    @Override
    @PostMapping("/items")
    public ResponseEntity<AddCartItemResponse> addItem(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        AddCartItemResult result = cartService.addItem(memberId, request.productOptionId(), request.quantity());
        // 새로 담았으면 201(새 자원 생성), 이미 담긴 옵션의 수량만 늘었으면 200
        HttpStatus status = result.merged() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(AddCartItemResponse.from(result));
    }

    @Override
    @GetMapping
    public ResponseEntity<CartResponse> getMyCart(
            @CurrentMemberId Long memberId
    ) {
        return ResponseEntity.ok(cartService.getMyCart(memberId));
    }

    @Override
    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<Void> changeQuantity(
            @CurrentMemberId Long memberId,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemQuantityRequest request
    ) {
        cartService.changeQuantity(memberId, cartItemId, request.quantity());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PatchMapping("/items/{cartItemId}/selection")
    public ResponseEntity<Void> changeSelected(
            @CurrentMemberId Long memberId,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemSelectionRequest request
    ) {
        cartService.changeSelected(memberId, cartItemId, request.selected());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PatchMapping("/items/selection")
    public ResponseEntity<Void> changeAllSelected(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody UpdateCartItemSelectionRequest request
    ) {
        cartService.changeAllSelected(memberId, request.selected());
        return ResponseEntity.noContent().build();
    }

    @Override
    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> deleteItem(
            @CurrentMemberId Long memberId,
            @PathVariable Long cartItemId
    ) {
        cartService.deleteItem(memberId, cartItemId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @DeleteMapping("/items/selected")
    public ResponseEntity<Void> deleteSelectedItems(
            @CurrentMemberId Long memberId
    ) {
        cartService.deleteSelectedItems(memberId);
        return ResponseEntity.noContent().build();
    }
}