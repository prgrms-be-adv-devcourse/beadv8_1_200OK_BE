package com.ok.wallet.adapter.in.event;

import com.ok.wallet.adapter.in.event.dto.OrderPaymentExpireRequestedMessage;
import com.ok.wallet.application.dto.OrderPaymentExpireCommand;
import com.ok.wallet.application.port.in.OrderPaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문이 결제 요청 뒤 오래 결과를 받지 못해 보내는 결제 만료 요청을 받아, 그 주문의 결과를 정하고 Kafka로 보낸다.
 * 주문은 결과가 올 때까지 같은 요청을 다시 보내고, 서비스는 올 때마다 처음 결과를 보낸다.
 */
@Component
@RequiredArgsConstructor
public class OrderPaymentExpireKafkaListener {
    private final OrderPaymentUseCase orderPaymentUseCase;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = WalletKafkaTopics.ORDER_PAYMENT_EXPIRE_REQUESTED,
            groupId = WalletKafkaTopics.PAYMENT_EXPIRE_GROUP,
            containerFactory = WalletKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.wallet.adapter.in.event.dto.OrderPaymentExpireRequestedMessage")
    void on(OrderPaymentExpireRequestedMessage message) {
        // 값이 틀리면 서비스가 IllegalArgumentException을 던지고, 지갑 오류 처리기가 다시 시도하지 않고 원문과 함께 실패 기록에 남긴다.
        // DB 오류나 결과 보내기 실패는 예외를 그대로 전파해 다시 받는다
        orderPaymentUseCase.expireAndSendResult(
                new OrderPaymentExpireCommand(message.orderId(), message.buyerId(), message.totalAmount()));
    }
}
