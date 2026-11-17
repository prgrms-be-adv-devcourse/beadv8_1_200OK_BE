package com.ok.order.cart.adapter.in.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 장바구니가 받는 Kafka 토픽도 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다(정산과 같은 방식).
 * 회원이 먼저 떠서 토픽을 만들기 전이나 주문만 따로 띄울 때 토픽이 없으면, 리스너가 "토픽 없음" 경고를 계속 찍기 때문이다.
 * 회원도 같은 토픽을 선언하지만 있으면 그대로라 충돌하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class CartKafkaTopicConfig {

    // 빈 이름에 cart를 넣는다. 회원도 같은 토픽을 선언하므로, 한 앱(monolith)에서 빈 이름이 겹치면 앱이 시작되지 않는다
    @Bean
    NewTopic cartMemberRegisteredTopic() {
        return TopicBuilder.name(CartInboundTopics.MEMBER_REGISTERED).build();
    }
}
