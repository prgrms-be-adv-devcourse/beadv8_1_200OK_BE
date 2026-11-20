package com.ok.order.order.adapter.in.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 주문이 받는 Kafka 토픽도 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다(정산과 같은 방식).
 * 보내는 쪽(지갑 #640, 배송 #644)이 아직 Kafka로 보내지 않아 토픽이 없으면, 리스너가 "토픽 없음" 경고를 계속 찍기 때문이다.
 * 나중에 보내는 쪽이 같은 토픽을 선언해도 있으면 그대로라 충돌하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class OrderInboundKafkaTopicConfig {

    // 빈 이름에 order를 넣는다. 지갑·정산도 같은 토픽을 선언하므로, 한 앱(monolith)에서 빈 이름이 겹치면 앱이 시작되지 않는다
    @Bean
    NewTopic orderWalletPaymentCompletedTopic() {
        return TopicBuilder.name(WalletTopics.WALLET_PAYMENT_COMPLETED).build();
    }

    @Bean
    NewTopic orderWalletPaymentFailedTopic() {
        return TopicBuilder.name(WalletTopics.WALLET_PAYMENT_FAILED).build();
    }

    @Bean
    NewTopic orderShipmentDispatchedTopic() {
        return TopicBuilder.name(DeliveryTopics.SHIPMENT_DISPATCHED).build();
    }

    @Bean
    NewTopic orderShipmentDeliveredTopic() {
        return TopicBuilder.name(DeliveryTopics.SHIPMENT_DELIVERED).build();
    }

    @Bean
    NewTopic orderRefundOrderRevertRequestedTopic() {
        return TopicBuilder.name(RefundTopics.REFUND_ORDER_REVERT_REQUESTED).build();
    }
}
