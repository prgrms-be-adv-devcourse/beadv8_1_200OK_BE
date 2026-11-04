package com.ok.refund.service.dto;

/**
 * 판매자가 환불 요청에 내리는 결정.
 * 도메인 상태({@code RefundStatus})와 따로 둔다. 판매자가 고르는 것은 "승인·거절" 두 가지이고, 그 결과 상태는 도메인이 정한다.
 */
public enum RefundDecision {

    /** 승인해 처리를 시작한다 (처리 중) */
    APPROVE,
    /** 거절한다 (사유 필수) */
    REJECT
}
