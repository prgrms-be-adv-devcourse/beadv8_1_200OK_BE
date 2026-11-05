package com.ok.payout.adapter.in.event;

import com.ok.common.event.WalletPaymentCompletedEvent;
import com.ok.payout.application.port.in.PayoutWalletDeductionUseCase;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 지갑이 발행하는 결제 완료 이벤트를 Spring 이벤트로 받는 입구다.
 * Kafka 입구({@link PayoutWalletKafkaListener})와 같은 입력 포트를 호출한다.
 * 지갑 차감 이벤트는 주문도 받지만(결제 완료 처리), 정산은 교차 검증용 기록을 위해 별도 리스너로 함께 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutWalletEventListener {

    private final PayoutWalletDeductionUseCase payoutWalletDeductionUseCase;

    // 지갑 모듈과 약속한 이벤트를 정산 서비스의 입력(커맨드)으로 바꿔 넘긴다
    // 이벤트 클래스(common.event)는 이벤트 어댑터(adapter/in/event, adapter/out/event)에서만 쓴다 (ArchitectureTest가 검사). 서비스는 이벤트 모양이 바뀌어도 영향받지 않는다
    // 모놀리스 안 Spring 이벤트로 받는 길 (지갑이 지금 보내는 방식)
    // TODO(#343): 지갑이 Spring 발행을 지우면 이 클래스(Spring 입구)를 통째로 지운다. 그때 정산에서 common.event를 받는 곳이 없어진다
    @ApplicationModuleListener
    void on(WalletPaymentCompletedEvent event) {
        WalletPaymentCompletedCommand command = new WalletPaymentCompletedCommand(
                event.orderId(),
                event.buyerId(),
                event.amount(),
                event.paidAt()
        );
        // 공용 이벤트(WalletPaymentCompletedEvent, common)의 생성자가 이미 null을 막으므로, 이 길에서는 음수 금액만 검사에 걸린다
        // 값이 잘못된 이벤트는 다시 처리해도 결과가 같으므로, 차감 기록을 남기지 않고 기록만 남긴 뒤 정상 종료해 재처리 대상에서 뺀다.
        // 확인은 트랜잭션이 걸린 서비스를 부르기 전에 끝낸다. 서비스 안에서 예외가 나면 트랜잭션이 롤백 전용이 되어,
        // 이 리스너(REQUIRES_NEW)가 정상 종료할 수 없다.
        Optional<String> invalidReason = PayoutInboundEventValidator.findInvalidReason(command);
        if (invalidReason.isPresent()) {
            log.error("[PAYOUT_WALLET_DEDUCTION_SKIPPED] 처리할 수 없는 지갑 결제 이벤트라 차감 기록을 남기지 않습니다. reason={}, command={}",
                    invalidReason.get(), command);
            return;
        }
        // DB 오류 같은 일시적 실패는 예외를 그대로 전파해 재처리 대상으로 남긴다.
        payoutWalletDeductionUseCase.saveDeduction(command);
    }
}
