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
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Invalid idempotency key", "Idempotency-Key 헤더는 공백이 아닌 64자 이하여야 합니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key conflict", "같은 주문 요청이 동시에 처리되었습니다. 잠시 후 다시 시도해 주세요."),
    ORDER_CREATION_IN_PROGRESS(HttpStatus.CONFLICT, "Order creation in progress", "같은 주문 요청을 처리하고 있습니다. 잠시 후 주문 내역을 확인해 주세요."),
    ORDER_AMOUNT_CHANGED(HttpStatus.CONFLICT, "Order amount changed", "주문 금액이 변경되었습니다. 금액을 다시 확인해 주세요."),
    CANNOT_ORDER_OWN_PRODUCT(HttpStatus.BAD_REQUEST, "Cannot order own product", "본인이 판매하는 상품은 주문할 수 없습니다."),
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Order item not found", "주문 상품을 찾을 수 없습니다."),
    ALREADY_PURCHASE_CONFIRMED(HttpStatus.CONFLICT, "Already purchase confirmed", "이미 구매확정된 상품입니다."),
    PURCHASE_CONFIRM_NOT_ALLOWED(HttpStatus.CONFLICT, "Purchase confirm not allowed", "배송 완료된 상품만 구매확정할 수 있습니다."),

    // 환불 취소 반영
    // 이미 출고는 정상 경쟁이라 409, 수량 초과·refundId 충돌은 두 모듈 기록이 어긋난 경우라 422로 나눈다.
    // 응답 type은 PROBLEM_BASE_URI가 기본값이면 about:blank라, 환불이 상태 코드만으로 거절과 멈춤을 가르게 하려는 것이다(#735, 환불 #554)
    REFUND_NOT_ALLOWED_STATUS(HttpStatus.CONFLICT, "Refund not allowed status", "출고 전(배송 준비) 상품만 환불을 반영할 수 있습니다."),
    REFUND_QUANTITY_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Refund quantity exceeded", "환불 수량 합계가 주문 수량을 넘습니다."),
    REFUND_ID_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "Refund id conflict", "같은 환불 ID로 다른 내용이 이미 반영되었습니다."),
    // 되돌린 환불의 기록 요청이 늦게 왔다. 되살리면 실패로 끝난 환불의 수량이 다시 빠지므로 기록이 어긋난 경우와 같이 422로 알린다(#604)
    REFUND_ALREADY_REVERTED(HttpStatus.UNPROCESSABLE_ENTITY, "Refund already reverted", "취소 기록을 이미 되돌린 환불입니다."),

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