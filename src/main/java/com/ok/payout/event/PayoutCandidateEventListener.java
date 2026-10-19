package com.ok.payout.event;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.service.PayoutCandidateService;
import com.ok.payout.service.dto.PurchaseConfirmedCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class PayoutCandidateEventListener {
    private final PayoutCandidateService payoutCandidateService;

    // 주문 모듈과 약속한 이벤트를 정산 서비스의 입력(커맨드)으로 바꿔 넘긴다
    // 이벤트 클래스는 event 패키지에서만 쓴다 (ArchitectureTest가 검사). 서비스는 이벤트 모양이 바뀌어도 영향받지 않는다
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
