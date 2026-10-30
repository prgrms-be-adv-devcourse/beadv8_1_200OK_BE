package com.ok.payout.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.ok.payout.event.PayoutTopics;

/**
 * 정산이 보내는 Kafka 토픽을 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다.
 * 브로커 자동 생성에 맡기지 않는 이유: 토픽이 코드에 남고, 받는 쪽(지갑)이 먼저 떠도 토픽이 있어 경고가 나지 않는다 (#339).
 * 파티션·복제 수는 지정하지 않고 브로커 기본값을 따른다 (운영은 복제 3, 테스트 브로커는 1대).
 */
@Configuration(proxyBeanMethods = false)
public class PayoutKafkaTopicConfig {

    @Bean
    public NewTopic sellerPayoutCreatedTopic() {
        return TopicBuilder.name(PayoutTopics.SELLER_PAYOUT_CREATED).build();
    }
}
