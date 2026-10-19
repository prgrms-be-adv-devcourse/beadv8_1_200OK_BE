package com.ok.payout.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.payout.service.PayoutWalletDeductionService;
import com.ok.payout.service.dto.WalletPaymentCompletedCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// 지갑이 발행하는 이벤트를 정산이 받는 곳
// 지갑 차감 이벤트는 주문도 받지만(결제 완료 처리), 정산은 교차 검증용 기록을 위해 별도 리스너로 함께 받음
@Component
@RequiredArgsConstructor
public class PayoutWalletEventListener {

    private final PayoutWalletDeductionService payoutWalletDeductionService;

    // 지갑 모듈과 약속한 이벤트를 정산 서비스의 입력(커맨드)으로 바꿔 넘긴다
    // 이벤트 클래스는 event 패키지에서만 쓴다 (ArchitectureTest가 검사). 서비스는 이벤트 모양이 바뀌어도 영향받지 않는다
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
