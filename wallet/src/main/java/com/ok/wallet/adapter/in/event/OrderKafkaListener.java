package com.ok.wallet.adapter.in.event;

import com.ok.wallet.adapter.in.event.dto.OrderCreatedMessage;
import com.ok.wallet.application.dto.OrderPaymentCommand;
import com.ok.wallet.application.port.in.OrderPaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문이 Kafka로 보내는 주문 생성을 받아 예치금으로 결제하고 결과를 Kafka로 보낸다.
 * 같은 주문이 여러 번 와도 서비스가 주문 ID로 한 번만 차감하고, 올 때마다 처음 결과를 다시 보낸다.
 */
@Component
@RequiredArgsConstructor
public class OrderKafkaListener {
    private final OrderPaymentUseCase orderPaymentUseCase;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = WalletKafkaTopics.ORDER_CREATED,
            groupId = WalletKafkaTopics.ORDER_PAYMENT_GROUP,
            containerFactory = WalletKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.wallet.adapter.in.event.dto.OrderCreatedMessage")
    void on(OrderCreatedMessage message) {
        // 다른 수신부와 달리 값 확인을 서비스에 맡긴다. 금액이 틀린 주문도 결제 대기로 멈추지 않게 실패 결과를 보내야 해서다.
        // 주문·구매자 ID가 없으면 서비스가 IllegalArgumentException을 던지고, 지갑 오류 처리기가 다시 시도하지 않고 원문과 함께 실패 기록에 남긴다.
        // DB 오류나 결과 보내기 실패는 예외를 그대로 전파한다. 지갑 오류 처리기가 다시 시도하고, 그때 서비스가 처음 결과를 다시 보낸다
        orderPaymentUseCase.payAndSendResult(
                new OrderPaymentCommand(
                        message.orderId(), message.buyerId(), message.totalAmount(), message.orderSummary()));
    }
}
