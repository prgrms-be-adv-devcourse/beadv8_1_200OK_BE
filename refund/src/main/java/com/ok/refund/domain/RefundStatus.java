package com.ok.refund.domain;

/**
 * 환불 요청 상태.
 *
 * <p>{@code REQUESTED → REJECTED | WITHDRAWN | PROCESSING}, {@code PROCESSING → NOT_APPROVABLE | COMPLETED}.
 * 처리 중에서 요청됨으로 돌아가는 길은 없다. 배송 건이 없으면 처음부터 처리 중으로 바꾸지 않기 때문이다
 * (되돌리면 그 요청을 처리 중으로 세어 배송비를 받은 다른 요청과 어긋나 배송비가 두 번 나간다).
 */
public enum RefundStatus {

    /** 판매자 승인 대기. 구매자가 철회할 수 있다 */
    REQUESTED,
    /** 판매자가 거절함 (사유 필수) */
    REJECTED,
    /** 구매자가 승인 전에 거둠 */
    WITHDRAWN,
    /** 승인 후 배송 잠금 ~ 잠금 해제 진행 중. 중간에 멈추면 여기에 남고 멈춘 단계를 기록한다 */
    PROCESSING,
    /** 배송 잠금이 거절됨 (이미 출고 등). 반품을 안내한다 */
    NOT_APPROVABLE,
    /** 돈을 돌려주고 모두 끝남 */
    COMPLETED;

    /**
     * 끝 상태인지 확인한다. 끝 상태가 되면 같은 주문 상품에 새 요청을 받을 수 있다.
     */
    public boolean isFinished() {
        return this == REJECTED || this == WITHDRAWN || this == NOT_APPROVABLE || this == COMPLETED;
    }

    /**
     * 판매자 묶음 전부 취소 판단에 넣는 상태인지 확인한다.
     * 처리 중은 승인 뒤 되돌리지 않으므로 결국 완료되거나 묶음 전체가 출고되어 승인 불가가 된다. 그래서 완료와 같이 센다.
     */
    public boolean countsAsCancelled() {
        return this == PROCESSING || this == COMPLETED;
    }
}
