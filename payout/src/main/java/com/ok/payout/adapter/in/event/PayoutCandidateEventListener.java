package com.ok.payout.adapter.in.event;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.adapter.in.event.dto.OrderProductConfirmedMessage;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.application.port.in.PayoutCandidateUseCase;
import com.ok.payout.application.dto.PurchaseConfirmedCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class PayoutCandidateEventListener {
    private final PayoutCandidateUseCase payoutCandidateService;

    // 주문 모듈과 약속한 이벤트를 정산 서비스의 입력(커맨드)으로 바꿔 넘긴다
    // 이벤트 클래스는 adapter/in/event 패키지에서만 쓴다 (ArchitectureTest가 검사). 서비스는 이벤트 모양이 바뀌어도 영향받지 않는다
    // 모놀리스 안 Spring 이벤트로 받는 길 (주문이 지금 보내는 방식)
    // TODO(#343): 주문이 Spring 발행을 지우면 이 메서드를 지운다. 그때 정산에서 common.event를 받는 곳이 없어진다
    @ApplicationModuleListener
    void on(OrderProductConfirmedEvent event) {
        payoutCandidateService.createFromPurchaseConfirmed(new PurchaseConfirmedCommand(
                event.orderId(),
                event.orderItemId(),
                event.sellerId(),
                event.totalItemPrice(),
                event.sellerShippingPrice()
        ));
    }

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
