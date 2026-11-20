package com.ok.testsupport;

import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.domain.Refund;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 주문 모듈이 하듯이 취소 기록 되돌리기 결과(되돌림·실패, #622) 메시지를 실제 Kafka(테스트 컨테이너) 토픽에 보내는 테스트 도우미다.
 * 주문 코드는 쓰지 않고, 주문이 보내기로 약속한 JSON 모양(PR #537, 타입 헤더 없이 키는 orderId)을 직접 만든다.
 * 기다리는 방법은 {@link RefundWalletResultSender#awaitUntil}을 함께 쓴다. {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 간다.
 */
@Component
public class RefundOrderResultSender {

    // 토픽 이름은 보내는 쪽(주문)과의 약속이다. 리스너의 상수를 가져다 쓰면 상수가 틀려도 테스트가 같이 틀려 못 잡으므로 약속한 글자를 그대로 적는다
    public static final String REVERTED_TOPIC = "order.order-refund-reverted.v1";
    public static final String REVERT_FAILED_TOPIC = "order.order-refund-revert-failed.v1";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired
    private RefundPersistencePort refundPersistencePort;

    /** 저장된 환불의 값(orderId, orderItemId)으로 되돌림 메시지를 보낸다. 기다리지 않는다 */
    public void sendReverted(Long refundId) {
        Refund refund = refundPersistencePort.findById(refundId).orElseThrow();
        send(REVERTED_TOPIC, message(refund, null));
    }

    /** 저장된 환불의 값으로 되돌리기 실패 메시지를 보낸다. 기다리지 않는다 */
    public void sendRevertFailed(Long refundId, String reason) {
        Refund refund = refundPersistencePort.findById(refundId).orElseThrow();
        send(REVERT_FAILED_TOPIC, message(refund, reason));
    }

    private static Map<String, Object> message(Refund refund, String reason) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("refundId", refund.getId());
        message.put("orderId", refund.getOrderId());
        message.put("orderItemId", refund.getOrderItemId());
        if (reason != null) {
            message.put("reason", reason);
        }
        return message;
    }

    // 키는 orderId(주문이 orderId로 보낸다)
    private void send(String topic, Map<String, Object> message) {
        kafkaTemplate.send(topic, String.valueOf(message.get("orderId")), message).join();
    }
}
