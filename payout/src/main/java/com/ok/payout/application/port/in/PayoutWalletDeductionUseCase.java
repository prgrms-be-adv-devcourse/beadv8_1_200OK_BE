package com.ok.payout.application.port.in;

/**
 * 지갑 차감 기록 저장 입력 포트. 구현은 {@code application.service}에 있다.
 */
public interface PayoutWalletDeductionUseCase {

    /**
     * 지갑 차감 기록을 저장한다. 같은 주문의 기록이 이미 있으면 무시한다(멱등).
     */
    void saveDeduction(WalletPaymentCompletedCommand command);
}
