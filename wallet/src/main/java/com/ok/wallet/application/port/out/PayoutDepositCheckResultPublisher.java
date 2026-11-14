package com.ok.wallet.application.port.out;

import com.ok.wallet.application.dto.PayoutDepositCheckResult;

/**
 * 정산 입금 확인의 답을 정산 모듈에 보내는 출력 포트. 구현은 {@code adapter.out.event}에 있다.
 */
public interface PayoutDepositCheckResultPublisher {

    /**
     * 한 묶음의 답을 보낸다. 받는 쪽에 저장된 것을 확인한 뒤 돌아온다.
     *
     * @throws IllegalStateException 보내지 못했으면
     */
    void publish(PayoutDepositCheckResult result);
}
