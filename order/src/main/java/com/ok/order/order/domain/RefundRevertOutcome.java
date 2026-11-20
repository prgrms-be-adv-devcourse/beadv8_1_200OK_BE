package com.ok.order.order.domain;

/**
 * 환불이 요청한 취소 기록 되돌리기의 결과다(#604).
 * 되돌림({@link #REVERTED}, {@link #ALREADY_REVERTED})이면 환불은 실패로 끝내고, 나머지는 환불에 이유로 보내 사람이 보게 한다.
 * 실패 결과의 이름은 그대로 환불에 보내는 이유({@code reason}) 값이라, 바꾸면 환불과 먼저 맞춘다.
 */
public enum RefundRevertOutcome {

    /** 이번에 되돌렸다 */
    REVERTED,
    /** 이미 되돌린 환불이다. 같은 요청이 다시 온 것이라 다시 되돌림으로 답한다 */
    ALREADY_REVERTED,
    /** 주문 상품이 없거나 메시지의 주문 ID와 맞지 않는다 */
    ORDER_ITEM_NOT_FOUND,
    /** 이 주문 상품에 그 환불 ID로 남긴 취소 기록이 없다 */
    REFUND_NOT_FOUND,
    /** 요청 수량이 남긴 취소 기록의 수량과 다르다 */
    QUANTITY_MISMATCH,
    /** 구매확정된 상품이다. 정산이 이미 시작돼 되돌리면 금액이 어긋난다 */
    ALREADY_CONFIRMED,
    /** 상품이나 같은 판매자 묶음이 이미 출고됐다. 되돌려도 배송 시작은 다시 오지 않아 상품이 나갈 길이 없다 */
    ALREADY_SHIPPED;

    public boolean isReverted() {
        return this == REVERTED || this == ALREADY_REVERTED;
    }
}
