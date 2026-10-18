package com.ok.order.order.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

    // 주문
    EMPTY_ORDER_ITEMS(HttpStatus.BAD_REQUEST, "Empty order items", "주문할 상품을 선택해 주세요."),
    THIRD_PARTY_CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "Third party consent required", "판매자에게 주문 정보를 제공하는 데 동의해 주세요."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found", "주문을 찾을 수 없습니다."),
    ORDER_AMOUNT_CHANGED(HttpStatus.CONFLICT, "Order amount changed", "주문 금액이 변경되었습니다. 금액을 다시 확인해 주세요."),
    CANNOT_ORDER_OWN_PRODUCT(HttpStatus.BAD_REQUEST, "Cannot order own product", "본인이 판매하는 상품은 주문할 수 없습니다."),

    // 상품
    PRODUCT_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Product option not found", "상품 옵션을 찾을 수 없습니다."),
    PRODUCT_NOT_ON_SALE(HttpStatus.CONFLICT, "Product not on sale", "판매 중인 상품이 아닙니다."),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "Out of stock", "재고가 부족한 상품이 있습니다."),
    STOCK_ALLOCATION_FAILED(HttpStatus.CONFLICT, "Stock allocation failed", "재고 확보에 실패했습니다. 잠시 후 다시 시도해 주세요."),

    // 지갑
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "Insufficient balance", "잔액이 부족합니다."),

    PRODUCT_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Product API failed", "상품 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    WALLET_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Wallet API failed", "잔액을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    DELIVERY_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Delivery API failed", "배송비를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    @Override
    public String slug() {
        return "order/" + ErrorCode.super.slug();
    }
}