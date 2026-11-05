package com.ok.payout.adapter.in.event;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.application.port.in.PayoutCandidateUseCase;
import com.ok.payout.application.port.in.PurchaseConfirmedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 주문 구매확정을 Spring 이벤트로 받는 입구다.
 * Kafka 입구({@link PayoutCandidateKafkaListener})와 같은 입력 포트를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutCandidateEventListener {
    private final PayoutCandidateUseCase payoutCandidateUseCase;

    // 주문 모듈과 약속한 이벤트를 정산 서비스의 입력(커맨드)으로 바꿔 넘긴다
    // 이벤트 클래스(common.event)는 이벤트 어댑터(adapter/in/event, adapter/out/event)에서만 쓴다 (ArchitectureTest가 검사). 서비스는 이벤트 모양이 바뀌어도 영향받지 않는다
    // 모놀리스 안 Spring 이벤트로 받는 길 (주문이 지금 보내는 방식)
    // TODO(#343): 주문이 Spring 발행을 지우면 이 클래스(Spring 입구)를 통째로 지운다. 그때 정산에서 common.event를 받는 곳이 없어진다
    @ApplicationModuleListener
    void on(OrderProductConfirmedEvent event) {
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(
                event.orderId(),
                event.orderItemId(),
                event.sellerId(),
                event.totalItemPrice(),
                event.sellerShippingPrice()
        );
        // 값이 잘못된 이벤트는 다시 처리해도 결과가 같으므로, 정산후보를 만들지 않고 기록만 남긴 뒤 정상 종료해 재처리 대상에서 뺀다.
        // 확인은 트랜잭션이 걸린 서비스를 부르기 전에 끝낸다. 서비스 안에서 예외가 나면 트랜잭션이 롤백 전용이 되어,
        // 이 리스너(REQUIRES_NEW)가 정상 종료할 수 없다.
        Optional<String> invalidReason = PayoutInboundEventValidator.findInvalidReason(command);
        if (invalidReason.isPresent()) {
            log.error("[PAYOUT_CANDIDATE_SKIPPED] 처리할 수 없는 구매확정 이벤트라 정산후보를 만들지 않습니다. reason={}, command={}",
                    invalidReason.get(), command);
            return;
        }
        // DB 오류 같은 일시적 실패는 예외를 그대로 전파해 재처리 대상으로 남긴다.
        payoutCandidateUseCase.createFromPurchaseConfirmed(command);
    }
}
