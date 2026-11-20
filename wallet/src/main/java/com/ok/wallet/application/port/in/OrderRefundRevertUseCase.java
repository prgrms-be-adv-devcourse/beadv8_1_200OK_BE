package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.OrderRefundRevertCommand;

/**
 * 환불이 요청한 되돌리기를 처리하고 결과를 환불에 알리는 입력 포트.
 */
public interface OrderRefundRevertUseCase {

    /**
     * 돌려준 예치금을 다시 가져오거나 가져오지 못한 결과를 기록하고, 그 결과를 환불에 보낸다. 같은 되돌리기가 다시 오면 처음 결과를 다시 보낸다.
     *
     * @throws IllegalArgumentException 환불·주문·구매자 ID가 없거나 금액이 0 이하이면. 다시 처리해도 결과가 같다
     * @throws IllegalStateException    결과를 보내지 못했으면. 되돌리기는 이미 기록돼 있어 다시 처리하면 처음 결과를 다시 보낸다
     */
    void revert(OrderRefundRevertCommand command);
}
