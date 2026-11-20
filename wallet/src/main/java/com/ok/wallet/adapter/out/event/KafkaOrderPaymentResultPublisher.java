package com.ok.wallet.adapter.out.event;

import com.ok.wallet.adapter.out.event.dto.WalletPaymentCompletedMessage;
import com.ok.wallet.adapter.out.event.dto.WalletPaymentFailedMessage;
import com.ok.wallet.application.dto.OrderPaymentFailureReason;
import com.ok.wallet.application.port.out.OrderPaymentResultPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * {@link OrderPaymentResultPublisher}의 Kafka 구현체. 한 건마다 저장 확인까지 기다리고({@link WalletKafkaSender}),
 * 실패하면 주문 ID를 담은 예외를 던진다. 같은 결과가 두 번 가도 주문과 정산은 주문 ID로 한 번만 반영한다.
 */
@Component
@RequiredArgsConstructor
class KafkaOrderPaymentResultPublisher implements OrderPaymentResultPublisher {

    private final WalletKafkaSender walletKafkaSender;

    @Override
    public void publishPaymentCompleted(Long orderId, Long buyerId, long amount, LocalDateTime paidAt) {
        send(WalletOutboundTopics.WALLET_PAYMENT_COMPLETED, orderId,
                new WalletPaymentCompletedMessage(orderId, buyerId, amount, paidAt));
    }

    @Override
    public void publishPaymentFailed(Long orderId, Long buyerId, OrderPaymentFailureReason reason) {
        send(WalletOutboundTopics.WALLET_PAYMENT_FAILED, orderId,
                new WalletPaymentFailedMessage(orderId, buyerId, reason.name()));
    }

    // 키를 orderId로 두면 한 주문의 완료와 실패가 같은 파티션으로 가 보낸 순서대로 받는다. 받는 요청(order.order-created.v1)의 키와 같다
    private void send(String topic, Long orderId, Object message) {
        walletKafkaSender.send(topic, String.valueOf(orderId), message,
                "주문 결제 결과를 보내지 못했습니다. topic=" + topic + ", orderId=" + orderId);
    }
}
