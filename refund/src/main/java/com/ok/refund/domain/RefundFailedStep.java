package com.ok.refund.domain;

/**
 * 승인 처리 중 멈춘 단계. 흐름 순서대로 둔다.
 * 1차 구현에는 자동 재시도가 없어, 운영자가 이 값을 보고 어디부터 다시 할지 판단한다.
 */
public enum RefundFailedStep {

    /** 흐름 5번 배송 잠금. 응답이 없어 잠금이 걸렸는지 모르는 경우다 (거절 409는 승인 불가로 끝나 여기 오지 않는다) */
    SHIPMENT_RESERVE,
    /** 흐름 6번 지갑 환불 */
    WALLET,
    /** 흐름 7번 주문 취소 수량 반영 */
    ORDER,
    /** 흐름 8번 상품 재고 되돌리기 */
    PRODUCT,
    /** 흐름 9번 배송 잠금 해제 */
    SHIPMENT_CONFIRM
}
