package com.ok.order.order.application.port.in;

import com.ok.order.order.application.dto.RevertOrderItemRefundCommand;

/**
 * 환불이 요청한 주문 상품 취소 기록 되돌리기를 처리하고 결과를 환불에 알린다(#604).
 */
public interface RevertOrderItemRefundUseCase {

    /**
     * 되돌릴 수 있으면 취소 기록을 되돌리고 되돌림을, 아니면 이유와 함께 실패를 환불에 보낸다.
     * 같은 요청이 다시 오면 다시 되돌림으로 답한다. 업무상 되돌릴 수 없는 경우는 예외 없이 실패 결과로 끝낸다.
     */
    void revertRefund(RevertOrderItemRefundCommand command);
}
