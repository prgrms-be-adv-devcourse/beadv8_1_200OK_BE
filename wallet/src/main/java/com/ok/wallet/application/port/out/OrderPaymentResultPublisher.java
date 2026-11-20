package com.ok.wallet.application.port.out;

import com.ok.wallet.application.dto.OrderPaymentFailureReason;

import java.time.LocalDateTime;

/**
 * 주문 결제 결과를 Kafka로 주문·정산에 알리는 출력 포트. 구현은 {@code adapter.out.event}에 있다.
 * 결제를 커밋한 뒤 보내고, 실패하면 예외로 같은 요청을 다시 받는다.
 */
public interface OrderPaymentResultPublisher {

    /**
     * 결제 금액을 차감했다고 알린다. 받는 쪽에 저장된 것을 확인한 뒤 돌아온다.
     *
     * @param paidAt 처리 기록의 시각. 같은 주문을 다시 보내도 처음 시각이 나간다
     * @throws IllegalStateException 보내지 못했으면
     */
    void publishPaymentCompleted(Long orderId, Long buyerId, long amount, LocalDateTime paidAt);

    /**
     * 결제 금액을 차감하지 못했다고 알린다. 받는 쪽에 저장된 것을 확인한 뒤 돌아온다.
     *
     * @throws IllegalStateException 보내지 못했으면
     */
    void publishPaymentFailed(Long orderId, Long buyerId, OrderPaymentFailureReason reason);
}
