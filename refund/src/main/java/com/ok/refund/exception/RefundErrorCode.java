package com.ok.refund.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RefundErrorCode implements ErrorCode {

    // 환불 요청
    INVALID_REFUND_QUANTITY(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid refund quantity", "취소 개수는 1 이상이어야 합니다."),
    REFUND_QUANTITY_EXCEEDED(HttpStatus.CONFLICT, "Refund quantity exceeded", "취소할 수 있는 개수를 넘었습니다."),
    REFUND_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "Refund already in progress", "이 상품에 처리 중인 환불 요청이 있습니다."),
    // 일부 취소로 무료배송 기준이 깨져 기본 배송비를 빼야 하는데 취소 금액이 그보다 작을 때. 승인 때 자동 거절 사유로도 같은 문구를 쓴다
    PARTIAL_CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "Partial cancel not allowed",
            "취소 금액이 남은 상품의 배송비보다 적어 이 상품만 취소할 수 없습니다."),

    // 승인·거절·철회
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "Refund not found", "환불 요청을 찾을 수 없습니다."),
    INVALID_REFUND_STATE(HttpStatus.CONFLICT, "Invalid refund state", "현재 상태에서는 처리할 수 없는 환불 요청입니다."),
    REJECT_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "Reject reason required", "거절 사유를 입력해 주세요."),

    // 주문 상품
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Order item not found", "주문 상품을 찾을 수 없습니다."),
    ORDER_ITEM_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Order item not cancellable", "출고 전 상품만 취소할 수 있습니다."),

    // 주문 모듈 호출
    ORDER_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Order API failed", "주문 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String title;
    private final String message;

    @Override
    public HttpStatus status() {
        return httpStatus;
    }

    // 컨벤션의 type slug는 "<모듈>/<에러 코드 이름>"인데 기본 구현은 이름만 돌려주므로 모듈 접두사를 붙인다
    @Override
    public String slug() {
        return "refund/" + ErrorCode.super.slug();
    }
}
