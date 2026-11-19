package com.ok.order.order.application.port.out;

import com.ok.order.order.application.dto.PaidOrderMessage;

/**
 * 주문이 다른 모듈에 보낼 메시지를 남긴다. 메서드마다 남긴 outbox 줄 ID를 돌려준다.
 */
public interface OrderEventPublisher {
    /**
     * 지갑에 결제를 요청한다. 주문을 잠그고 결제 요청 시각을 기록하는 트랜잭션 안에서 부른다.
     *
     * @param orderSummary 지갑 이용 내역에 보일 주문 요약(예: "사과 외 3건"). 항상 값이 있다
     */
    Long publishOrderCreatedEvent(Long orderId, Long buyerId, Long totalAmount, String orderSummary);
    Long publishOrderProductConfirmedEvent(Long orderId, Long orderItemId, Long sellerId, Long totalItemPrice, Long sellerShippingPrice);
    Long publishOrderPaidEvent(PaidOrderMessage message);

    /**
     * 결제를 요청하고 오래 결과가 없는 주문의 결제 만료를 지갑에 요청한다(#656). 지갑은 결제했으면 결제 완료를,
     * 아니면 결제 실패(PAYMENT_EXPIRED)를 보낸다. 트랜잭션 안에서 부른다.
     *
     * @param totalAmount 결제 요청한 금액(원). 지갑이 처음 보는 주문이면 이 금액으로 만료 실패를 먼저 기록한다
     */
    Long publishPaymentExpireRequested(Long orderId, Long buyerId, Long totalAmount);

    /**
     * 환불이 요청한 취소 기록 되돌리기를 마쳤다고 환불에 알린다(#604). 환불은 이 결과로 환불을 실패로 끝낸다. 트랜잭션 안에서 부른다.
     */
    Long publishRefundReverted(Long refundId, Long orderId, Long orderItemId);

    /**
     * 취소 기록을 되돌리지 못했다고 이유와 함께 환불에 알린다(#604). 환불은 처리 중으로 남겨 사람이 보게 한다. 트랜잭션 안에서 부른다.
     *
     * @param reason 되돌리지 못한 이유. {@code RefundRevertOutcome}의 이름이다(예: {@code ALREADY_CONFIRMED})
     */
    Long publishRefundRevertFailed(Long refundId, Long orderId, Long orderItemId, String reason);
}
