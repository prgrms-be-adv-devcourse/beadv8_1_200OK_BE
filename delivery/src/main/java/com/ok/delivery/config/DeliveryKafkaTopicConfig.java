package com.ok.delivery.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 배송이 받는 Kafka 토픽을 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다.
 * 보내는 쪽(member)이 아직 토픽을 만들지 않았어도 리스너가 "토픽 없음" 경고를 계속 찍지 않게 한다.
 * 파티션·복제 수는 지정하지 않고 브로커 기본값을 따른다.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryKafkaTopicConfig {

    @Bean
    public NewTopic sellerOriginPostalCodeTopic() {
        return TopicBuilder.name(DeliveryTopics.SELLER_ORIGIN_POSTAL_CODE).build();
    }
}
