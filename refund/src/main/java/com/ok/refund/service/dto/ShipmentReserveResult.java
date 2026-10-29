package com.ok.refund.service.dto;

/**
 * 배송 잠금(취소 예약) 결과.
 */
public enum ShipmentReserveResult {

    /** 잠금 성공 또는 같은 환불ID로 이미 잠겨 있음 */
    RESERVED,
    /**
     * 배송이 409로 거절함 (이미 출고, 수량 초과 등). 이유는 나누지 않고 모두 "승인 불가"로 처리한다.
     * 오류 응답에 코드 칸이 없어(PROBLEM_BASE_URI=about:blank) 한국어 문장으로만 구분되기 때문이다
     */
    REJECTED
}
