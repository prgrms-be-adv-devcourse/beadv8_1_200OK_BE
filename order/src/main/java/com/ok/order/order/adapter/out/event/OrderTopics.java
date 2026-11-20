package com.ok.order.order.adapter.out.event;

/**
 * 주문이 Kafka로 보내는 토픽 이름. 이름은 {@code <모듈>.<이벤트>.v1} 규칙을 따른다(#648).
 * 받는 쪽 모듈은 이 클래스를 import하지 않고 같은 문자열을 자기 모듈에 따로 둔다(모듈 경계).
 */
final class OrderTopics {

    // 주문 생성 → 지갑 결제 요청 (#602)
    static final String ORDER_CREATED = "order.order-created.v1";

    // 구매확정 → 정산 후보 등록 (#600)
    static final String ORDER_PRODUCT_CONFIRMED = "order.order-product-confirmed.v1";

    // 결제 완료 → 배송 생성 (#603)
    static final String ORDER_PAID = "order.order-paid.v1";

    // 결제 요청 뒤 결과가 없는 주문의 결제 만료 요청 → 지갑 (#656)
    static final String ORDER_PAYMENT_EXPIRE_REQUESTED = "order.order-payment-expire-requested.v1";

    // 환불 취소 기록 되돌림 → 환불 (#604)
    static final String ORDER_REFUND_REVERTED = "order.order-refund-reverted.v1";

    // 환불 취소 기록 되돌리기 실패 → 환불 (#604)
    static final String ORDER_REFUND_REVERT_FAILED = "order.order-refund-revert-failed.v1";

    private OrderTopics() {
    }
}
