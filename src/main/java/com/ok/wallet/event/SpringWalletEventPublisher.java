package com.ok.wallet.event;

import com.ok.common.event.DomainEvent;
import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.common.event.WalletPaymentFailedEvent;
import com.ok.wallet.service.WalletEventPublisher;
import com.ok.wallet.service.dto.OrderPaymentFailureReason;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 지갑 모듈 이벤트를 스프링 이벤트로 발행한다.
 *
 * <p>서비스가 넘긴 값으로 모듈 간 이벤트({@link DomainEvent})를 만들어 발행한다.
 * 발행은 호출한 서비스의 트랜잭션 안에서 일어나고, 구독하는 모듈은 커밋 후에 처리한다({@code @ApplicationModuleListener}).
 */
@Component
@RequiredArgsConstructor
public class SpringWalletEventPublisher implements WalletEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishOrderPaymentCompleted(Long orderId, Long buyerId, long amount, LocalDateTime paidAt) {
        publish(new WalletPaymentCompletedEvent(orderId, buyerId, amount, paidAt));
    }

    @Override
    public void publishOrderPaymentFailed(Long orderId, Long buyerId, OrderPaymentFailureReason reason) {
        // 지갑 내부 사유를 이벤트 계약의 사유 문자열로 바꿔 보낸다 (common.event는 event 패키지에서만 쓴다)
        publish(new WalletPaymentFailedEvent(orderId, buyerId, toEventReason(reason)));
    }

    // 한쪽에 값이 추가되면 컴파일러가 빠진 경우를 알려 주도록 지갑 enum을 기준으로 하나씩 연결한다
    private static String toEventReason(OrderPaymentFailureReason reason) {
        return switch (reason) {
            case INSUFFICIENT_BALANCE -> WalletPaymentFailedEvent.INSUFFICIENT_BALANCE;
            case INVALID_PAYMENT_REQUEST -> WalletPaymentFailedEvent.INVALID_PAYMENT_REQUEST;
        };
    }

    // 모듈 간 계약으로 정한 이벤트만 나가도록 DomainEvent만 받는다
    private void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
