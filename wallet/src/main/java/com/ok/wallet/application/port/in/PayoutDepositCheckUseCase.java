package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.PayoutDepositCheckCommand;

/**
 * 정산이 묻는 정산금 입금 여부에 판매자 원장을 읽어 답하는 유스케이스.
 */
public interface PayoutDepositCheckUseCase {

    /**
     * 묶음의 정산마다 원장에 입금 기록이 있는지 읽고 답을 정산에 보낸다. 원장과 잔액은 바꾸지 않는다.
     * 같은 요청을 다시 받아도 원장이 그대로면 같은 답을 보낸다.
     *
     * @throws IllegalStateException 답을 보내지 못했으면. 같은 요청을 다시 받아 다시 답하게 그대로 올린다
     */
    void check(PayoutDepositCheckCommand command);
}
