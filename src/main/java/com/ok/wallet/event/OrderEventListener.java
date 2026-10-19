package com.ok.wallet.event;

import com.ok.common.event.OrderCreatedEvent;
import com.ok.wallet.service.OrderPaymentService;
import com.ok.wallet.service.dto.OrderPaymentCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 주문 모듈이 발행하는 이벤트를 받아 구매자 예치금 결제를 처리한다.
 */
@Component
@RequiredArgsConstructor
public class OrderEventListener {
    private final OrderPaymentService orderPaymentService;

    /**
     * 주문이 생성되면 결제 금액만큼 예치금을 차감하고, 결과를 결제 완료·실패 이벤트로 주문에 알린다.
     * 같은 주문 이벤트가 재발행으로 다시 와도 주문 ID로 한 번만 차감한다.
     *
     * <p>값 확인은 서비스가 맡는다. 금액이 잘못된 이벤트도 결제를 기다리는 주문이 멈추지 않도록 실패 이벤트로 알려야 해서,
     * 발행 인터페이스를 쓰는 서비스에서 판단한다. 서비스는 이 경우 예외 없이 정상 종료한다.
     * DB 오류 같은 일시적 실패는 예외를 그대로 전파해 재처리 대상으로 남긴다.
     */
    @ApplicationModuleListener
    void on(OrderCreatedEvent event) {
        orderPaymentService.pay(new OrderPaymentCommand(event.orderId(), event.buyerId(), event.totalAmount()));
    }
}
