package com.ok.testsupport;

import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 지갑 모듈이 하듯이 예치금 반환 결과(완료·실패) 메시지를 실제 Kafka(테스트 컨테이너) 토픽에 보내는 테스트 도우미다.
 * 지갑 코드는 쓰지 않고, 지갑이 보내기로 약속한 JSON 모양(타입 헤더 없이 키는 orderId)을 직접 만든다.
 * 환불 리스너는 별도 스레드에서 받으므로 결과는 {@link #awaitStatus}로 기다린 뒤 확인한다.
 * 상태 확인은 출력 포트(도메인)로 한다. {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가서 여러 통합 테스트가 함께 쓴다.
 */
@Component
public class RefundWalletResultSender {

    // 토픽 이름은 보내는 쪽(지갑)과의 약속이다. 리스너의 상수를 가져다 쓰면 상수가 틀려도 테스트가 같이 틀려 못 잡으므로 약속한 글자를 그대로 적는다
    public static final String COMPLETED_TOPIC = "wallet.wallet-refund-completed";
    public static final String FAILED_TOPIC = "wallet.wallet-refund-failed";

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private RefundPersistencePort refundPersistencePort;

    /** 저장된 환불의 값으로 완료 메시지를 보내고, 환불이 완료될 때까지 기다린다. 돌려줄 돈이 있는 승인 뒤에 완료까지 가게 할 때 쓴다 */
    public void completeByWallet(Long refundId) {
        sendCompleted(refundId);
        awaitStatus(refundId, RefundStatus.COMPLETED);
    }

    /** 저장된 환불의 값(orderId, buyerId, 환불 금액)으로 완료 메시지를 보낸다. 기다리지 않는다 */
    public void sendCompleted(Long refundId) {
        Refund refund = refundPersistencePort.findById(refundId).orElseThrow();
        send(COMPLETED_TOPIC, message(refund, refund.getRefundAmount(), null));
    }

    /** 저장된 환불의 값으로 실패 메시지를 보낸다. 기다리지 않는다 */
    public void sendFailed(Long refundId, String reason) {
        Refund refund = refundPersistencePort.findById(refundId).orElseThrow();
        send(FAILED_TOPIC, message(refund, refund.getRefundAmount(), reason));
    }

    /** 환불이 주어진 상태가 될 때까지 기다린다. 시간 안에 안 되면 마지막 상태와 함께 실패한다 */
    public void awaitStatus(Long refundId, RefundStatus expected) {
        awaitUntil("환불 " + refundId + "이(가) " + expected + "이(가) 되지 않았습니다",
                () -> refundPersistencePort.findById(refundId).map(Refund::getStatus).orElse(null) == expected);
    }

    /** 조건이 참이 될 때까지 짧게 쉬며 다시 본다. Awaitility를 쓰지 않으려고(의존 추가 없이) 직접 만든 한도 있는 반복이다 */
    public static void awaitUntil(String failureMessage, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(failureMessage + " (" + TIMEOUT.toSeconds() + "초 대기)");
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("기다리는 중 중단됐습니다", e);
            }
        }
    }

    private static Map<String, Object> message(Refund refund, long amount, String reason) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("refundId", refund.getId());
        message.put("orderId", refund.getOrderId());
        message.put("buyerId", refund.getBuyerId());
        message.put("amount", amount);
        if (reason != null) {
            message.put("reason", reason);
        }
        return message;
    }

    // 키는 orderId(지갑이 orderId로 보낸다)
    private void send(String topic, Map<String, Object> message) {
        kafkaTemplate.send(topic, String.valueOf(message.get("orderId")), message).join();
    }
}
