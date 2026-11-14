package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.WalletOrderRefundFailureReason;

/**
 * 환불 반환 결과를 환불 모듈에 알리는 출력 포트. 구현은 {@code adapter.out.event}에 있다.
 * 주문 결제 결과({@link WalletEventPublisher})와 나눈 이유: 결제 결과는 아직 Spring 이벤트로 나가고 반환 결과는 처음부터 Kafka로 나가,
 * 보내는 방식과 실패했을 때의 처리가 다르다.
 */
public interface WalletRefundResultPublisher {

    /**
     * 돌려줬다고 알린다. 받는 쪽에 저장된 것을 확인한 뒤 돌아온다.
     *
     * @throws IllegalStateException 보내지 못했으면
     */
    void publishRefundCompleted(Long refundId, Long orderId, Long buyerId, long amount);

    /**
     * 돌려주지 않고 거절했다고 알린다. 받는 쪽에 저장된 것을 확인한 뒤 돌아온다.
     *
     * @param amount 환불이 요청한 금액 그대로
     * @throws IllegalStateException 보내지 못했으면
     */
    void publishRefundFailed(Long refundId, Long orderId, Long buyerId, long amount,
                             WalletOrderRefundFailureReason reason);
}
