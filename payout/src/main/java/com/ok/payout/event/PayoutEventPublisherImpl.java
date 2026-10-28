package com.ok.payout.event;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.payout.service.dto.SellerPayoutCreatedCommand;
import com.ok.payout.service.publisher.PayoutEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

// PayoutEventPublisher의 구현체. 지금은 Spring 이벤트(ApplicationEventPublisher)로 발행한다
// ApplicationEventPublisher와 이벤트 클래스(common.event)는 event 패키지에서만 쓴다 (ArchitectureTest가 검사)
// 서비스가 넘긴 값을 지갑과 약속한 이벤트(SellerPayoutCreatedEvent)로 바꿔 발행하는 일만 한다
// 이 클래스는 다른 곳에서 직접 쓰지 않고 인터페이스로만 주입받으므로 public이 아니어도 된다
@Component
@RequiredArgsConstructor
class PayoutEventPublisherImpl implements PayoutEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void publishSellerPayoutCreated(SellerPayoutCreatedCommand command) {
        // 지갑 리스너는 커밋 후에 받는다(@ApplicationModuleListener). 발행하는 쪽 트랜잭션이 롤백되면 전달되지 않는다
        eventPublisher.publishEvent(new SellerPayoutCreatedEvent(
                command.payoutId(),
                command.payeeId(),
                command.amount(),
                command.createdAt()
        ));
    }
}
