package com.ok.order.order.adapter.out.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 주문이 보내는 Kafka 토픽을 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다.
 * 브로커 자동 생성에 맡기지 않는 이유: 토픽이 코드에 남고, 받는 쪽이 먼저 떠도 토픽이 있어 "토픽 없음" 경고가 나지 않는다.
 * 파티션·복제 수는 지정하지 않고 브로커 기본값을 따른다.
 * 아직 보내지 않는 토픽도 미리 선언한다. 토픽별 전환(#600, #602, #603)을 동시에 진행해도 이 파일을 함께 고치지 않게 하려는 것이다.
 */
@Configuration(proxyBeanMethods = false)
class OrderKafkaTopicConfig {

    // 빈 이름에 order를 넣는다. 받는 쪽(정산 등)도 같은 토픽을 선언하므로, 한 앱(monolith)에서 빈 이름이 겹치면 앱이 시작되지 않는다
    @Bean
    NewTopic orderOrderCreatedTopic() {
        return TopicBuilder.name(OrderTopics.ORDER_CREATED).build();
    }

    @Bean
    NewTopic orderOrderProductConfirmedTopic() {
        return TopicBuilder.name(OrderTopics.ORDER_PRODUCT_CONFIRMED).build();
    }

    @Bean
    NewTopic orderOrderPaidTopic() {
        return TopicBuilder.name(OrderTopics.ORDER_PAID).build();
    }
}
