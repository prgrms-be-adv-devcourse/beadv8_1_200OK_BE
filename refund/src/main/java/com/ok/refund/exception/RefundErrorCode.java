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
    ORDER_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Order API failed", "주문 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    // 주문이 취소 반영을 422로 막음: 수량 합계 초과, 같은 refundId에 다른 내용, 이미 되돌린 환불(#554, 주문 #737·#604). 환불과 주문 기록이 어긋난 것이라
    // 다시 해도 같아 단계를 바로 멈추고 사람이 본다. 주문 반영은 첫 단계라 돈·재고가 움직이기 전이고, 되돌릴 것도 없다.
    // 이유는 가르지 않는다. 이유 이름은 응답 type에만 오는데 about:blank일 수 있어 믿을 수 없고, 모두 사람이 보는 것은 같다.
    // 판매자 응답은 상품 거절(PRODUCT_RELEASE_REFUSED)과 같게 409로 둔다
    ORDER_RECORD_CONFLICT(HttpStatus.CONFLICT, "Order record conflict", "주문 기록과 맞지 않아 확인이 필요합니다. 관리자에게 문의해 주세요."),

    // 상품 모듈 호출 (승인 뒤 재고 되돌리기, #414)
    PRODUCT_API_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Product API failed", "상품 재고를 되돌리지 못했습니다. 잠시 후 다시 시도해 주세요."),
    // 상품이 다시 해도 결과가 같은 이유(해제 가능 수량 초과, 같은 환불 다른 내용, 상품 409)로 해제를 거절함. 다시 시도하지 않고 단계를 멈추고 지갑 돈을 다시 가져온다(#576, #546)
    PRODUCT_RELEASE_REFUSED(HttpStatus.CONFLICT, "Product release refused", "상품 재고를 되돌릴 수 없는 상태입니다. 관리자에게 문의해 주세요."),
    // 상품이 재고 배정 기록이 없다고 답함(404). 다시 해도 같아 단계를 멈추지만 돈은 다시 가져오지 않는다(#546).
    // 404는 상품 문서에 뜻이 없고, 주소 설정 오류·게이트웨이 404와 가를 수 없어 돈을 움직이는 근거로 쓰지 않는다. 판매자 응답은 거절과 같게 409로 둔다
    PRODUCT_STOCK_NOT_FOUND(HttpStatus.CONFLICT, "Product stock allocation not found", "상품 재고를 되돌릴 수 없는 상태입니다. 관리자에게 문의해 주세요.");

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
