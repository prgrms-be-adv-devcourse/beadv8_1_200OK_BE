package com.ok.refund.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RefundErrorCode implements ErrorCode {

    // 환불 요청
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "Refund not found", "환불 요청을 찾을 수 없습니다."),
    INVALID_REFUND_QUANTITY(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid refund quantity", "취소 개수는 1 이상이어야 합니다."),
    REFUND_QUANTITY_EXCEEDED(HttpStatus.CONFLICT, "Refund quantity exceeded", "취소할 수 있는 개수를 넘었습니다."),
    REFUND_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "Refund already in progress", "이 상품에 처리 중인 환불 요청이 있습니다."),
    INVALID_REFUND_STATE(HttpStatus.CONFLICT, "Invalid refund state", "현재 환불 상태에서는 처리할 수 없습니다."),
    REJECT_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "Reject reason required", "거절 사유는 필수입니다."),
    PARTIAL_CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "Partial cancel not allowed",
            "취소하면 남은 상품이 무료배송 기준보다 적어지는데, 취소 금액이 배송비보다 작아 일부만 취소할 수 없습니다. 전체 취소를 요청해 주세요."),

    // 주문 상품
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Order item not found", "주문 상품을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Order item not cancellable", "출고 전 상품만 취소할 수 있습니다."),

    // 다른 모듈 호출 실패
    ORDER_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Order API failed", "주문 정보를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    DELIVERY_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Delivery API failed", "배송 정보를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    PRODUCT_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Product API failed", "재고를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    WALLET_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Wallet API failed", "환불 금액을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    @Override
    public String slug() {
        return "refund/" + ErrorCode.super.slug();
    }
}
