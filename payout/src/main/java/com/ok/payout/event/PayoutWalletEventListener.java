package com.ok.payout.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.payout.event.dto.WalletPaymentCompletedMessage;
import com.ok.payout.service.PayoutWalletDeductionService;
import com.ok.payout.service.dto.WalletPaymentCompletedCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
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

    // Kafka로 받는 길. 지갑이 아직 Kafka로 보내지 않아도 미리 열어 둔다(#339)
    // 받는 타입은 정산 DTO. 메시지에 보내는 쪽 클래스 이름이 없어서(타입 헤더 없음) 리스너마다 읽을 타입을 정한다
    // 그룹을 payout으로 정해 두는 이유: 주문도 같은 결제 완료를 받으므로, 같은 그룹이면 메시지가 둘 중 한쪽에만 간다
    // Spring 이벤트와 Kafka로 같은 결제 완료가 두 번 와도 서비스가 orderId로 중복을 막는다
    @KafkaListener(
            topics = PayoutTopics.WALLET_PAYMENT_COMPLETED,
            groupId = PayoutTopics.CONSUMER_GROUP,
            properties = "spring.json.value.default.type=com.ok.payout.event.dto.WalletPaymentCompletedMessage")
    void onKafka(WalletPaymentCompletedMessage message) {
        payoutWalletDeductionService.saveDeduction(message.toCommand());
    }
}
