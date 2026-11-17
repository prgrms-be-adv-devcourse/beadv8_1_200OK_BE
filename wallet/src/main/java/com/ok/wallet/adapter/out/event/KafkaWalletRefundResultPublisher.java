package com.ok.wallet.adapter.out.event;

import com.ok.wallet.adapter.out.event.dto.WalletRefundCompletedMessage;
import com.ok.wallet.adapter.out.event.dto.WalletRefundFailedMessage;
import com.ok.wallet.application.port.out.WalletRefundResultPublisher;
import com.ok.wallet.domain.WalletOrderRefundFailureReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link WalletRefundResultPublisher}의 Kafka 구현체. 한 건마다 저장 확인까지 기다리고({@link WalletKafkaSender}),
 * 실패하면 환불 ID를 담은 예외를 던진다. 같은 환불 결과가 두 번 가도 환불은 한 번만 반영한다.
 */
@Component
@RequiredArgsConstructor
class KafkaWalletRefundResultPublisher implements WalletRefundResultPublisher {
    private final WalletKafkaSender walletKafkaSender;

    @Override
    public void publishRefundCompleted(Long refundId, Long orderId, Long buyerId, long amount) {
        send(WalletOutboundTopics.WALLET_REFUND_COMPLETED, refundId, orderId,
                new WalletRefundCompletedMessage(refundId, orderId, buyerId, amount));
    }

    @Override
    public void publishRefundFailed(Long refundId, Long orderId, Long buyerId, long amount,
                                    WalletOrderRefundFailureReason reason) {
        send(WalletOutboundTopics.WALLET_REFUND_FAILED, refundId, orderId,
                new WalletRefundFailedMessage(refundId, orderId, buyerId, amount, reason.name()));
    }

    // 키를 orderId로 두면 같은 주문의 결과가 같은 파티션으로 가 보낸 순서대로 받는다. 받는 요청(refund.refund-approved.v1)의 키와 같다
    private void send(String topic, Long refundId, Long orderId, Object message) {
        walletKafkaSender.send(topic, String.valueOf(orderId), message,
                "환불 반환 결과를 보내지 못했습니다. topic=" + topic + ", refundId=" + refundId);
    }
}
