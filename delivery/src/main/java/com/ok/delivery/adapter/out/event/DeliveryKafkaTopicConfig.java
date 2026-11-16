package com.ok.delivery.adapter.out.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 배송이 보내는 Kafka 토픽을 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다.
 * 받는 쪽(주문)이 먼저 떠도 토픽이 있어 "토픽 없음" 경고가 나지 않는다. 파티션·복제 수는 브로커 기본값을 따른다.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryKafkaTopicConfig {

    // 빈 이름에 delivery를 넣는다. 한 앱(monolith)에서 다른 모듈의 같은 이름 빈과 겹치면 앱이 시작되지 않는다
    @Bean
    NewTopic deliveryShipmentDispatchedTopic() {
        return TopicBuilder.name(DeliveryTopics.SHIPMENT_DISPATCHED).build();
    }

    @Bean
    NewTopic deliveryShipmentDeliveredTopic() {
        return TopicBuilder.name(DeliveryTopics.SHIPMENT_DELIVERED).build();
    }
}
