package com.ok.refund.domain;

/**
 * 환불 요청 상태.
 *
 * <p>{@code REQUESTED → PROCESSING | REJECTED | WITHDRAWN}. 처리 중에서 요청됨으로 돌아가는 길은 두지 않는다.
 * 승인한 뒤에는 배송 잠금·지갑 환불이 차례로 진행되어(#412~#414), 되돌리면 이미 움직인 돈·재고와 어긋난다.
 * 승인 불가·완료(#412, #414) 상태는 각 이슈에서 더한다.
 */
public enum RefundStatus {

    /** 판매자 승인 대기. 요청만으로는 돈·재고·배송이 바뀌지 않는다. 구매자가 철회할 수 있다 */
    REQUESTED,
    /** 판매자가 승인해 처리를 시작함. 아직 진행 중이라 같은 상품에 새 요청을 받지 않는다 */
    PROCESSING,
    /** 판매자가 거절함 (사유 필수). 끝난 상태 */
    REJECTED,
    /** 구매자가 승인 전에 거둠. 끝난 상태 */
    WITHDRAWN
}
