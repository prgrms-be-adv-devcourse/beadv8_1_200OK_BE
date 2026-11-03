package com.ok.refund.domain;

/**
 * 환불 요청 상태.
 * 지금은 요청 단계(#408)라 {@code REQUESTED}만 있다. 승인·거절·철회(#409), 승인 불가·완료(#412, #414) 상태는 각 이슈에서 더한다.
 */
public enum RefundStatus {

    /** 판매자 승인 대기. 요청만으로는 돈·재고·배송이 바뀌지 않는다 */
    REQUESTED
}
