package com.ok.order.order.adapter.in.event;

/**
 * 주문이 환불에게서 Kafka로 받는 토픽 이름과 주문 리스너의 컨슈머 그룹. 토픽 이름은 환불과의 약속이라 환불과 같은 값이어야 받는다(#604, 환불 #622).
 * 환불 클래스를 import하지 않고 같은 문자열을 여기 따로 둔다(모듈 경계).
 */
final class RefundTopics {

    // 환불 → 주문: 승인 뒤 끝내 실패한 환불의 취소 기록 되돌리기 요청. 키는 orderId
    static final String REFUND_ORDER_REVERT_REQUESTED = "refund.refund-order-revert-requested.v1";

    // 그룹 이름은 <모듈>.<용도>(#648)
    static final String REFUND_REVERT_GROUP = "order.refund-revert";

    private RefundTopics() {
    }
}
