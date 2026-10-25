package com.ok.order.cart.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.order.cart.controller.dto.*;
import com.ok.order.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class ApiV1CartController implements CartControllerDocs {

    private final CartService cartService;

    @Override
    @PostMapping("/items")
    public ResponseEntity<AddCartItemResponse> addItem(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        Long cartItemId = cartService.addItem(memberId, request.productOptionId(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AddCartItemResponse(cartItemId));
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