package com.ok.wallet.adapter.in.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 지갑이 받는 토픽 중 보내는 쪽이 아직 만들지 않는 토픽을 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다.
 * 결제 만료 요청은 지갑이 주문(#684)보다 먼저 머지되어, 토픽이 없으면 리스너가 "토픽 없음" 경고를 계속 찍는다.
 * 주문이 같은 토픽을 선언해도 있으면 그대로라 충돌하지 않는다(주문이 결제 결과 토픽을 미리 만드는 것과 같은 방식).
 */
@Configuration(proxyBeanMethods = false)
class WalletInboundKafkaTopicConfig {

    // 빈 이름에 wallet을 넣는다. 주문도 같은 토픽을 선언하므로, 한 앱(monolith)에서 빈 이름이 겹치면 앱이 시작되지 않는다
    @Bean
    NewTopic walletInboundOrderPaymentExpireRequestedTopic() {
        return TopicBuilder.name(WalletKafkaTopics.ORDER_PAYMENT_EXPIRE_REQUESTED).build();
    }
}
