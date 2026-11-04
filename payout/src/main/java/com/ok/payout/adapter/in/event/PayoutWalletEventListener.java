package com.ok.payout.adapter.in.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.payout.application.port.in.PayoutWalletDeductionUseCase;
import com.ok.payout.application.dto.WalletPaymentCompletedCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// 지갑이 발행하는 이벤트를 Spring 이벤트로 받는 입구 (Kafka 입구는 PayoutWalletKafkaListener)
// 지갑 차감 이벤트는 주문도 받지만(결제 완료 처리), 정산은 교차 검증용 기록을 위해 별도 리스너로 함께 받음
@Component
@RequiredArgsConstructor
public class PayoutWalletEventListener {

    private final PayoutWalletDeductionUseCase payoutWalletDeductionService;

    // 지갑 모듈과 약속한 이벤트를 정산 서비스의 입력(커맨드)으로 바꿔 넘긴다
    // 이벤트 클래스는 adapter/in/event 패키지에서만 쓴다 (ArchitectureTest가 검사). 서비스는 이벤트 모양이 바뀌어도 영향받지 않는다
    // 모놀리스 안 Spring 이벤트로 받는 길 (지갑이 지금 보내는 방식)
    // TODO(#343): 지갑이 Spring 발행을 지우면 이 메서드를 지운다. 그때 정산에서 common.event를 받는 곳이 없어진다
    @ApplicationModuleListener
    void on(WalletPaymentCompletedEvent event) {
        payoutWalletDeductionService.saveDeduction(new WalletPaymentCompletedCommand(
                event.orderId(),
                event.buyerId(),
                event.amount(),
                event.paidAt()
        ));
    }
}
