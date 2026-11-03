package com.ok.payout.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.ok.payout.event.PayoutTopics;

/**
 * 정산이 보내고 받는 Kafka 토픽을 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다.
 * 브로커 자동 생성에 맡기지 않는 이유: 토픽이 코드에 남고, 받는 쪽이 먼저 떠도 토픽이 있어 경고가 나지 않는다 (#339).
 * 파티션·복제 수는 지정하지 않고 브로커 기본값을 따른다 (운영은 복제 3, 테스트 브로커는 1대).
 */
@Configuration(proxyBeanMethods = false)
public class PayoutKafkaTopicConfig {

    @Bean
    public NewTopic sellerPayoutCreatedTopic() {
        return TopicBuilder.name(PayoutTopics.SELLER_PAYOUT_CREATED).build();
    }

    // 받는 토픽도 선언한다. 보내는 쪽(주문·지갑)이 아직 Kafka로 보내지 않아 토픽이 없으면, 리스너가 "토픽 없음" 경고를 계속 찍기 때문(#339)
    // NewTopic은 있으면 그대로라, 나중에 주문·지갑이 같은 선언을 해도 충돌하지 않는다

    @Bean
    public NewTopic orderProductConfirmedTopic() {
        return TopicBuilder.name(PayoutTopics.ORDER_PRODUCT_CONFIRMED).build();
    }

    @Bean
    public NewTopic walletPaymentCompletedTopic() {
        return TopicBuilder.name(PayoutTopics.WALLET_PAYMENT_COMPLETED).build();
    }
}
