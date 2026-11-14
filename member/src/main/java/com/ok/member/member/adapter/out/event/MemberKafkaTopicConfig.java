package com.ok.member.member.adapter.out.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 회원이 보내는 Kafka 토픽을 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다.
 * 브로커 자동 생성에 맡기지 않는 이유: 토픽이 코드에 남고, 받는 쪽(장바구니)이 먼저 떠도 토픽이 있어 "토픽 없음" 경고가 나지 않는다.
 * 파티션·복제 수는 지정하지 않고 브로커 기본값을 따른다.
 * Kafka 타입은 발행 구현체가 있는 이 패키지에서만 쓰도록 {@code config}가 아니라 여기 둔다(refund와 같은 배치).
 */
@Configuration(proxyBeanMethods = false)
class MemberKafkaTopicConfig {

    // 빈 이름에 member를 넣는다. 한 앱(monolith)에서 다른 모듈의 같은 이름 빈과 겹치면 앱이 시작되지 않는다
    @Bean
    NewTopic memberRegisteredTopic() {
        return TopicBuilder.name(MemberTopics.MEMBER_REGISTERED).build();
    }

    @Bean
    NewTopic memberSellerOriginPostalCodeTopic() {
        return TopicBuilder.name(MemberTopics.SELLER_ORIGIN_POSTAL_CODE).build();
    }
}
