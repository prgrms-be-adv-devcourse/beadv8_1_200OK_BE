package com.ok.wallet.application.dto;

import com.ok.wallet.domain.WalletOrderPayment;
import com.ok.wallet.domain.WalletOrderPaymentStatus;

import java.time.LocalDateTime;

/**
 * 주문 결제 요청을 처리한 결과. 주문에 보낼 결과의 값이 된다.
 * 잔액 부족도 결제 실패로 알려야 하는 정상 결과라 예외가 아니라 값으로 돌려준다.
 * 이미 처리한 주문이면 처음 기록의 값을 담는다. Kafka로 다시 받은 주문에 처음 결과를 그대로 다시 보내기 위해서다.
 *
 * @param processedAt      처리 기록의 시각. 결제 완료의 결제 시각이 된다
 * @param alreadyProcessed 이번 요청 전에 이미 처리한 주문이면 true. 이번에 잔액을 바꾸지 않았다는 뜻이다
 */
public record OrderPaymentResult(
        Long orderId,
        Long buyerId,
        long amount,
        WalletOrderPaymentStatus status,
        LocalDateTime processedAt,
        boolean alreadyProcessed
) {
    public static OrderPaymentResult processed(Long orderId, Long buyerId, long amount,
                                               WalletOrderPaymentStatus status, LocalDateTime processedAt) {
        return new OrderPaymentResult(orderId, buyerId, amount, status, processedAt, false);
    }

    public static OrderPaymentResult alreadyProcessed(WalletOrderPayment payment) {
        return new OrderPaymentResult(payment.getOrderId(), payment.getBuyerId(), payment.getAmount(),
                payment.getStatus(), payment.getCreatedAt(), true);
    }

    public boolean paid() {
        return status == WalletOrderPaymentStatus.PAID;
    }

    /**
     * 이번 요청의 구매자·금액이 처리 기록과 같은지 본다. 이미 처리한 주문이 다른 값으로 왔는지 가리는 데 쓴다.
     */
    public boolean sameRequest(Long requestedBuyerId, long requestedAmount) {
        return buyerId.equals(requestedBuyerId) && amount == requestedAmount;
    }

    /**
     * 실패한 결제를 주문에 알릴 사유를 돌려준다. 처음 처리할 때와 다시 보낼 때 같은 사유가 나가도록 사유는 여기서만 정한다.
     *
     * @throws IllegalStateException 결제된 결과면. 실패 사유가 없다
     */
    public OrderPaymentFailureReason failureReason() {
        // 상태를 모두 나열해, 처리 기록 상태가 늘면 사유를 정하지 않은 채 넘어가지 않도록 컴파일 오류로 드러낸다
        return switch (status) {
            case PAID -> throw new IllegalStateException("결제된 주문에는 실패 사유가 없습니다. orderId=" + orderId);
            case FAILED -> OrderPaymentFailureReason.INSUFFICIENT_BALANCE;
            case EXPIRED -> OrderPaymentFailureReason.PAYMENT_EXPIRED;
        };
    }
}
