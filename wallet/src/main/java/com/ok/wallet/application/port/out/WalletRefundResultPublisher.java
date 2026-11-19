package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.WalletOrderRefundFailureReason;
import com.ok.wallet.domain.WalletOrderRefundRevertFailureReason;

/**
 * 환불 반환 결과를 환불 모듈에 알리는 출력 포트. 구현은 {@code adapter.out.event}에 있다.
 * 주문 결제 결과({@link OrderPaymentResultPublisher})와 나눈 이유: 받는 모듈과 메시지가 달라, 받는 쪽마다 포트를 둔다.
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

    /**
     * 돌려준 예치금을 다시 가져왔다고 알린다. 돌려준 적 없는 환불도 이 결과로 알린다. 받는 쪽에 저장된 것을 확인한 뒤 돌아온다.
     *
     * @param buyerId 지갑이 실제로 돌려준 구매자
     * @param amount  지갑이 실제로 돌려준 금액
     * @throws IllegalStateException 보내지 못했으면
     */
    void publishRefundReverted(Long refundId, Long orderId, Long buyerId, long amount);

    /**
     * 돌려준 예치금을 다시 가져오지 못했다고 알린다. 받는 쪽에 저장된 것을 확인한 뒤 돌아온다.
     *
     * @throws IllegalStateException 보내지 못했으면
     */
    void publishRefundRevertFailed(Long refundId, Long orderId, Long buyerId, long amount,
                                   WalletOrderRefundRevertFailureReason reason);
}
