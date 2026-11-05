package com.ok.order.cart.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.order.cart.adapter.in.web.dto.AddCartItemRequest;
import com.ok.order.cart.adapter.in.web.dto.AddCartItemResponse;
import com.ok.order.cart.adapter.in.web.dto.CartResponse;
import com.ok.order.cart.adapter.in.web.dto.UpdateCartItemQuantityRequest;
import com.ok.order.cart.adapter.in.web.dto.UpdateCartItemSelectionRequest;
import com.ok.order.cart.application.dto.AddCartItemResult;
import com.ok.order.cart.application.port.in.AddCartItemUseCase;
import com.ok.order.cart.application.port.in.DeleteCartItemUseCase;
import com.ok.order.cart.application.port.in.GetCartUseCase;
import com.ok.order.cart.application.port.in.UpdateCartItemUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
// 장바구니는 구매를 위한 것이라 주문과 같이 구매 권한(BUYER)이 있는 회원만 쓴다
@PreAuthorize("hasRole('BUYER')")
@RequiredArgsConstructor
public class ApiV1CartController implements CartControllerSpec {

    private final AddCartItemUseCase addCartItem;
    private final GetCartUseCase getCart;
    private final UpdateCartItemUseCase updateCartItem;
    private final DeleteCartItemUseCase deleteCartItem;

    @Override
    @PostMapping("/items")
    public ResponseEntity<AddCartItemResponse> addItem(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        AddCartItemResult result = addCartItem.addItem(memberId, request.productOptionId(), request.quantity());
        // 새로 담았으면 201(새 자원 생성), 이미 담긴 옵션의 수량만 늘었으면 200
        HttpStatus status = result.merged() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(AddCartItemResponse.from(result));
    }

    @Override
    @GetMapping
    public ResponseEntity<CartResponse> getMyCart(
            @CurrentMemberId Long memberId
    ) {
        return ResponseEntity.ok(CartResponse.from(getCart.getMyCart(memberId)));
    }

    @Override
    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<Void> changeQuantity(
            @CurrentMemberId Long memberId,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemQuantityRequest request
    ) {
        updateCartItem.changeQuantity(memberId, cartItemId, request.quantity());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PatchMapping("/items/{cartItemId}/selection")
    public ResponseEntity<Void> changeSelected(
            @CurrentMemberId Long memberId,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemSelectionRequest request
    ) {
        updateCartItem.changeSelected(memberId, cartItemId, request.selected());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PatchMapping("/items/selection")
    public ResponseEntity<Void> changeAllSelected(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody UpdateCartItemSelectionRequest request
    ) {
        updateCartItem.changeAllSelected(memberId, request.selected());
        return ResponseEntity.noContent().build();
    }

    @Override
    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> deleteItem(
            @CurrentMemberId Long memberId,
            @PathVariable Long cartItemId
    ) {
        deleteCartItem.deleteItem(memberId, cartItemId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @DeleteMapping("/items/selected")
    public ResponseEntity<Void> deleteSelectedItems(
            @CurrentMemberId Long memberId
    ) {
        deleteCartItem.deleteSelectedItems(memberId);
        return ResponseEntity.noContent().build();
    }
}