package com.ok.payout.adapter.out.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.payout.application.port.out.PayoutModuleEventPublisher;
import com.ok.payout.application.port.out.SellerPayoutCreated;

import lombok.RequiredArgsConstructor;

/**
 * {@link PayoutModuleEventPublisher}의 Spring 이벤트 구현체.
 * 지갑 리스너(@ApplicationModuleListener)는 발행한 트랜잭션이 커밋된 뒤에 받는다.
 */
@Component
@RequiredArgsConstructor
class SpringPayoutEventPublisher implements PayoutModuleEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishSellerPayoutCreated(SellerPayoutCreated created) {
        applicationEventPublisher.publishEvent(new SellerPayoutCreatedEvent(
                created.payoutId(), created.payeeId(), created.amount(), created.createdAt()));
    }
}
