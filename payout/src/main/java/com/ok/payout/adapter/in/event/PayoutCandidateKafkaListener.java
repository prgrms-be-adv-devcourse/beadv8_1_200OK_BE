package com.ok.payout.adapter.in.event;

import com.ok.payout.adapter.in.event.dto.OrderProductConfirmedMessage;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.application.port.in.PayoutCandidateUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문 구매확정을 Kafka로 받는 입구다.
 * Spring 이벤트 입구({@link PayoutCandidateEventListener})는 #343에서 지워질 예정이라 따로 분리했고, 두 입구는 같은 입력 포트를 호출한다.
 */
@Component
@RequiredArgsConstructor
public class PayoutCandidateKafkaListener {
    private final PayoutCandidateUseCase payoutCandidateService;

    // Kafka로 받는 길. 주문이 아직 Kafka로 보내지 않아도 미리 열어 둔다(#339)
    // 받는 타입은 정산 DTO. 메시지에 보내는 쪽 클래스 이름이 없어서(타입 헤더 없음) 리스너마다 읽을 타입을 정한다
    // 그룹을 payout으로 정해 두는 이유: 기본값이 앱 이름이라 monolith에서는 다른 모듈과 같은 그룹이 되어 메시지가 한쪽에만 간다
    // Spring 이벤트와 Kafka로 같은 구매확정이 두 번 와도 서비스가 orderItemId로 중복을 막는다
    @KafkaListener(
            topics = PayoutTopics.ORDER_PRODUCT_CONFIRMED,
            groupId = PayoutTopics.CONSUMER_GROUP,
            properties = "spring.json.value.default.type=com.ok.payout.adapter.in.event.dto.OrderProductConfirmedMessage")
    void onKafka(OrderProductConfirmedMessage message) {
        payoutCandidateService.createFromPurchaseConfirmed(message.toCommand());
    }
}
