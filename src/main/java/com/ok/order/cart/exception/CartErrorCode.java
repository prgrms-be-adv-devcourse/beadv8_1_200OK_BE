package com.ok.order.cart.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CartErrorCode implements ErrorCode {

    // 회원
    MEMBER_CANNOT_PURCHASE(HttpStatus.FORBIDDEN, "Member cannot purchase", "구매할 수 없는 회원입니다."),

    // 상품
    PRODUCT_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Product option not found", "상품 옵션을 찾을 수 없습니다."),
    PRODUCT_NOT_ON_SALE(HttpStatus.CONFLICT, "Product not on sale", "판매 중인 상품이 아닙니다."),

    // 장바구니
    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "Cart not found", "장바구니를 찾을 수 없습니다."),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Cart item not found", "장바구니 상품을 찾을 수 없습니다."),
    CANNOT_ADD_OWN_PRODUCT(HttpStatus.BAD_REQUEST, "Cannot add own product", "본인이 판매하는 상품은 담을 수 없습니다."),
    CART_ITEM_ALREADY_EXISTS(HttpStatus.CONFLICT, "Cart item already exists", "이미 장바구니에 담긴 상품입니다."),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "Invalid quantity", "수량은 1개 이상이어야 합니다.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    @Override
    public String slug() {
        return "order/cart/" + ErrorCode.super.slug();
    }
}
