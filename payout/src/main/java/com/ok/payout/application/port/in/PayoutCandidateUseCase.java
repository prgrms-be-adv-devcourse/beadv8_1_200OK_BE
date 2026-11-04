package com.ok.payout.application.port.in;

import com.ok.payout.application.dto.PurchaseConfirmedCommand;

/**
 * 정산후보 생성 입력 포트. 구현은 {@code application.service}에 있다.
 */
public interface PayoutCandidateUseCase {

    /**
     * 구매확정된 주문상품으로 정산후보를 만든다. 같은 주문상품이 이미 처리됐으면 무시한다(멱등).
     */
    void createFromPurchaseConfirmed(PurchaseConfirmedCommand command);
}
