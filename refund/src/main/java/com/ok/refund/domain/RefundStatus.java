package com.ok.refund.domain;

/**
 * 환불 요청 상태.
 *
 * <p>{@code REQUESTED → PROCESSING | REJECTED | WITHDRAWN}, {@code PROCESSING → COMPLETED}. 처리 중에서 요청됨으로 돌아가는 길은 두지 않는다.
 * 승인한 뒤에는 지갑 환불·주문 반영·재고 복구가 차례로 진행되어(#413~#414), 되돌리면 이미 움직인 돈·재고와 어긋난다.
 * 이미 출고돼 승인할 수 없는 경우(#412)는 자동 거절로 끝낸다.
 */
public enum RefundStatus {

    /** 판매자 승인 대기. 요청만으로는 돈·재고·배송이 바뀌지 않는다. 구매자가 철회할 수 있다 */
    REQUESTED,
    /** 판매자가 승인해 처리를 시작함. 아직 진행 중이라 같은 상품에 새 요청을 받지 않는다 */
    PROCESSING,
    /** 판매자가 거절함 (사유 필수). 끝난 상태 */
    REJECTED,
    /** 구매자가 승인 전에 거둠. 끝난 상태 */
    WITHDRAWN,
    /** 승인 뒤 지갑 반환 요청·주문 취소 반영·재고 복구를 모두 마침(#414). 끝난 상태. 취소된 개수에는 계속 센다 */
    COMPLETED
}
