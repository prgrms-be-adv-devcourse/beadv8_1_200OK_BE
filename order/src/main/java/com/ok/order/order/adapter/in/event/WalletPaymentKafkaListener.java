package com.ok.order.order.adapter.in.event;

import com.ok.order.order.adapter.in.event.dto.WalletPaymentCompletedMessage;
import com.ok.order.order.adapter.in.event.dto.WalletPaymentFailedMessage;
import com.ok.order.order.application.port.in.CompletePaymentUseCase;
import com.ok.order.order.application.port.in.FailPaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 지갑이 Kafka로 보내는 결제 완료·실패를 받는다(#602).
 * 지갑이 Kafka로 옮기는 동안(#640 머지 전후)에는 Spring 수신부({@link WalletPaymentEventListener})도 함께 있다.
 * 같은 결과가 두 길로 와도 결제 완료·실패 처리가 주문 상태를 보고 두 번째는 무시하므로 한 번만 반영된다.
 * <p>
 * 리스너에 {@code @Transactional}을 붙이지 않는다. 결제 실패는 서비스가 자기 트랜잭션으로 먼저 커밋한 뒤 재고 해제 HTTP를 부른다(#657).
 * 처리 중 예외는 주문 오류 처리기({@link OrderKafkaConsumerConfig})가 다시 시도한 뒤 실패 기록 표에 남긴다.
 */
@Component
@RequiredArgsConstructor
class WalletPaymentKafkaListener {

    private final CompletePaymentUseCase completePayment;
    private final FailPaymentUseCase failPayment;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = WalletTopics.WALLET_PAYMENT_COMPLETED,
            groupId = WalletTopics.PAYMENT_RESULT_GROUP,
            containerFactory = OrderKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.order.order.adapter.in.event.dto.WalletPaymentCompletedMessage")
    void onCompleted(WalletPaymentCompletedMessage message) {
        completePayment.completePayment(requireOrderId(message.orderId()));
    }

    @KafkaListener(
            topics = WalletTopics.WALLET_PAYMENT_FAILED,
            groupId = WalletTopics.PAYMENT_RESULT_GROUP,
            containerFactory = OrderKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.order.order.adapter.in.event.dto.WalletPaymentFailedMessage")
    void onFailed(WalletPaymentFailedMessage message) {
        failPayment.failPayment(requireOrderId(message.orderId()), message.reason());
    }

    // 주문 ID가 없으면 다시 받아도 처리할 수 없다. IllegalArgumentException은 오류 처리기가 다시 시도하지 않고 바로 실패 기록에 남긴다
    private Long requireOrderId(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("결제 결과에 주문 ID가 없습니다.");
        }
        return orderId;
    }
}
