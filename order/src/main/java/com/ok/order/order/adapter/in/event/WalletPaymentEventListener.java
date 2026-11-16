package com.ok.order.order.adapter.in.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.common.event.WalletPaymentFailedEvent;
import com.ok.order.order.application.port.in.CompletePaymentUseCase;
import com.ok.order.order.application.port.in.FailPaymentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// 지갑이 Kafka로 옮기는 동안 함께 두는 Spring 수신부다. Kafka 수신부는 WalletPaymentKafkaListener.
// TODO(#602): 지갑이 Kafka로만 보내면(#640 머지 뒤) 이 리스너를 지운다
@Component
@RequiredArgsConstructor
public class WalletPaymentEventListener {

    private final CompletePaymentUseCase completePayment;
    private final FailPaymentUseCase failPayment;

    @ApplicationModuleListener
    void on(WalletPaymentCompletedEvent event) {
        completePayment.completePayment(event.orderId());
    }

    // @ApplicationModuleListener에서 트랜잭션만 뺀 형태다(비동기, 커밋 뒤 수신, Modulith 처리 기록은 그대로).
    // 리스너 트랜잭션이 있으면 결제 실패와 재고 해제 HTTP가 한 트랜잭션에 묶여, 해제가 실패하면 결제 실패까지 롤백되고
    // 응답을 기다리는 동안 주문 행 잠금과 DB 커넥션을 쥔다. 결제 실패는 서비스가 자기 트랜잭션으로 먼저 커밋한다(#657)
    @Async
    @TransactionalEventListener
    void on(WalletPaymentFailedEvent event) {
        failPayment.failPayment(event.orderId(), event.reason());
    }
}
