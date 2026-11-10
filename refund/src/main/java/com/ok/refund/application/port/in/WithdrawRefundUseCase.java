package com.ok.refund.application.port.in;

/**
 * 구매자가 본인 환불 요청을 승인 전에 거두는 입력 포트.
 */
public interface WithdrawRefundUseCase {

    /**
     * 구매자가 본인 요청을 승인 전에 거둔다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND}, 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    RefundResult withdraw(Long refundId, Long buyerId);
}
