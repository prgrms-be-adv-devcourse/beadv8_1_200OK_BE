package com.ok.wallet.service.dto;

/**
 * 주문 결제 차감 결과. 잔액 부족도 결제 실패 이벤트로 알려야 하는 정상 결과라 예외가 아니라 값으로 돌려준다.
 * (예외로 트랜잭션이 롤백되면 같은 트랜잭션에서 실패 이벤트를 발행할 수 없다)
 */
public enum OrderPaymentResult {
    PAID,                 // 차감하고 원장에 기록함
    ALREADY_PAID,         // 같은 주문으로 이미 차감됨 (재발행된 이벤트)
    INSUFFICIENT_BALANCE  // 잔액이 부족해 차감하지 않음
}
