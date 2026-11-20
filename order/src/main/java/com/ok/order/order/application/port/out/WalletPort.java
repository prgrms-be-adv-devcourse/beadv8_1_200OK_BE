package com.ok.order.order.application.port.out;

/**
 * 주문이 지갑 모듈에 부탁하는 일. 구현은 {@code adapter.out.wallet}에 있다.
 */
public interface WalletPort {

    /**
     * 구매자의 예치금 잔액(원)을 조회한다.
     */
    long getBalance(Long memberId);
}
