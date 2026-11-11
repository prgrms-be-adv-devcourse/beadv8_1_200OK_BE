package com.ok.refund.adapter.out.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 환불이 보내는 Kafka 토픽을 앱이 시작할 때 만든다. 이미 있으면 그대로 둔다.
 * 브로커 자동 생성에 맡기지 않는 이유: 토픽이 코드에 남고, 받는 쪽(지갑)이 먼저 떠도 토픽이 있어 "토픽 없음" 경고가 나지 않는다.
 * 파티션·복제 수는 지정하지 않고 브로커 기본값을 따른다.
 * {@code config} 패키지가 아니라 여기 두는 이유: Kafka 타입(TopicBuilder)은 이벤트 어댑터 안에서만 쓴다({@code ArchitectureTest}의 메시징 규칙).
 */
@Configuration(proxyBeanMethods = false)
class RefundKafkaTopicConfig {

    // 빈 이름에 refund를 넣는다. 한 앱(monolith)에서 다른 모듈의 같은 이름 빈과 겹치면 앱이 시작되지 않는다 (refund/CLAUDE.md 6절)
    @Bean
    NewTopic refundApprovedTopic() {
        return TopicBuilder.name(RefundTopics.REFUND_APPROVED).build();
    }
}
