package com.ok.wallet.service.dto;

/**
 * 주문 결제 차감 결과. 잔액 부족도 결제 실패 이벤트로 알려야 하는 정상 결과라 예외가 아니라 값으로 돌려준다.
 * (예외로 트랜잭션이 롤백되면 같은 트랜잭션에서 실패 이벤트를 발행할 수 없다)
 */
public enum OrderPaymentResult {
    PAID,                 // 차감하고 결과를 기록함
    INSUFFICIENT_BALANCE, // 잔액이 부족해 차감하지 않고 실패로 기록함
    ALREADY_PAID,         // 이미 차감된 주문 (재전달된 요청)
    ALREADY_FAILED        // 이미 잔액 부족으로 실패한 주문 (재전달된 요청)
}
