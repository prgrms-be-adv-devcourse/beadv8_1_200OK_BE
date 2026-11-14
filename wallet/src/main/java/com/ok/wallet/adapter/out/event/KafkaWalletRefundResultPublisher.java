package com.ok.wallet.adapter.out.event;

import com.ok.wallet.adapter.out.event.dto.WalletRefundCompletedMessage;
import com.ok.wallet.adapter.out.event.dto.WalletRefundFailedMessage;
import com.ok.wallet.application.port.out.WalletRefundResultPublisher;
import com.ok.wallet.domain.WalletOrderRefundFailureReason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link WalletRefundResultPublisher}의 Kafka 구현체. 한 건마다 브로커의 저장 확인(acks=all)을 최대 {@link #SEND_TIMEOUT}까지 기다리고,
 * 실패하면 환불 ID를 담은 예외를 던진다. 예외가 리스너까지 올라가야 같은 요청을 다시 받아 결과를 다시 보낸다.
 * KafkaTemplate은 스프링 부트가 yaml(spring.kafka)로 만든 것을 그대로 쓴다. 지갑 전용으로 새로 만들면 한 앱(monolith)에
 * KafkaTemplate이 둘이 되어 다른 모듈의 주입이 모호해질 수 있다.
 */
@Component
class KafkaWalletRefundResultPublisher implements WalletRefundResultPublisher {
    // 저장 확인을 기다리는 최대 시간. 정상이면 1초 안에 끝난다. Kafka 기본값(delivery.timeout.ms 120초)까지 기다리면
    // 브로커 장애 때 리스너가 한 메시지에 오래 묶여 다시 시도까지 늦어진다
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Duration sendTimeout;

    @Autowired
    KafkaWalletRefundResultPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this(kafkaTemplate, SEND_TIMEOUT);
    }

    // 테스트가 시간 초과 경로를 10초씩 기다리지 않고 확인할 수 있게 대기 시간을 바꿔 넣는다
    KafkaWalletRefundResultPublisher(KafkaTemplate<String, Object> kafkaTemplate, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

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

    // 키를 orderId로 두면 같은 주문의 결과가 같은 파티션으로 가 보낸 순서대로 받는다. 받는 요청(refund.refund-approved)의 키와 같다
    private void send(String topic, Long refundId, Long orderId, Object message) {
        try {
            kafkaTemplate.send(topic, String.valueOf(orderId), message)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw sendFailed(topic, refundId, e);
        } catch (ExecutionException e) {
            throw sendFailed(topic, refundId, e.getCause());
        } catch (TimeoutException e) {
            // 기다리기만 그만둔 것이라 메시지는 뒤에서 계속 보내질 수 있다. 다시 보내 같은 결과가 두 번 가도 환불은 한 번만 반영한다
            throw sendFailed(topic, refundId, e);
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 환불인지 남긴다
            throw sendFailed(topic, refundId, e);
        }
    }

    private static IllegalStateException sendFailed(String topic, Long refundId, Throwable cause) {
        return new IllegalStateException("환불 반환 결과를 보내지 못했습니다. topic=" + topic + ", refundId=" + refundId, cause);
    }
}
