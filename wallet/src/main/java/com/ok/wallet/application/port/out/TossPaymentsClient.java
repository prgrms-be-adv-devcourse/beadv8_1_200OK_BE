package com.ok.wallet.application.port.out;

import com.ok.wallet.application.dto.TossConfirmResult;

/**
 * 토스페이먼츠 결제 승인 출력 포트. 구현은 {@code adapter.out.toss}에 있다. 트랜잭션 밖에서 호출해야 한다.
 * 응답을 승인·명확한 실패·불명확 세 가지로 나누며, 확실하지 않으면 불명확으로 둔다.
 */
public interface TossPaymentsClient {

    /**
     * 결제를 승인한다. 같은 충전 건으로 다시 호출해도 토스가 한 번만 승인하도록 충전 번호를 멱등 키로 쓴다.
     *
     * @param paymentKey 토스가 결제창에서 발급한 결제 키
     * @param chargeNo   충전 번호 (토스의 orderId)
     * @param amount     서버에 저장된 충전 금액
     */
    TossConfirmResult confirm(String paymentKey, String chargeNo, long amount);
}
