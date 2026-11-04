package com.ok.payout.adapter.in.event;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.application.port.in.PayoutCandidateUseCase;
import com.ok.payout.application.dto.PurchaseConfirmedCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// 주문 구매확정을 Spring 이벤트로 받는 입구 (Kafka 입구는 PayoutCandidateKafkaListener)
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
}
