package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.ChargeConfirmResult;

/**
 * 토스 결제 승인 유스케이스.
 */
public interface ChargeConfirmUseCase {

    /**
     * 충전 건을 토스에 승인 요청하고 결과를 반영한다.
     * 승인 여부를 알 수 없거나 반영에 실패하면 실패로 돌려주지 않고 처리 중으로 돌려준다.
     */
    ChargeConfirmResult confirm(Long memberId, String chargeNo, String paymentKey, long amount);
}
