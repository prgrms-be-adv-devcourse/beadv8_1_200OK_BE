package com.ok.payout.adapter.in.event;

import com.ok.payout.adapter.in.event.dto.WalletPaymentCompletedMessage;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.application.port.in.PayoutWalletDeductionUseCase;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 지갑이 발행하는 결제 완료를 Kafka로 받는 입구다.
 * 지갑 차감 이벤트는 주문도 받지만(결제 완료 처리), 정산은 교차 검증용 기록을 위해 별도 리스너로 함께 받는다.
 * Spring 이벤트 입구({@link PayoutWalletEventListener})는 #343에서 지워질 예정이라 따로 분리했고, 두 입구는 같은 입력 포트를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutWalletKafkaListener {

    private final PayoutWalletDeductionUseCase payoutWalletDeductionUseCase;

    // Kafka로 받는 길. 지갑이 아직 Kafka로 보내지 않아도 미리 열어 둔다(#339)
    // 받는 타입은 정산 DTO. 메시지에 보내는 쪽 클래스 이름이 없어서(타입 헤더 없음) 리스너마다 읽을 타입을 정한다
    // 그룹을 payout으로 정해 두는 이유: 주문도 같은 결제 완료를 받으므로, 같은 그룹이면 메시지가 둘 중 한쪽에만 간다
    // Spring 이벤트와 Kafka로 같은 결제 완료가 두 번 와도 서비스가 orderId로 중복을 막는다
    @KafkaListener(
            topics = PayoutTopics.WALLET_PAYMENT_COMPLETED,
            groupId = PayoutTopics.CONSUMER_GROUP,
            properties = "spring.json.value.default.type=com.ok.payout.adapter.in.event.dto.WalletPaymentCompletedMessage")
    void onKafka(WalletPaymentCompletedMessage message) {
        WalletPaymentCompletedCommand command = message.toCommand();
        // 값이 잘못된 메시지는 다시 받아도 결과가 같으므로, 차감 기록을 남기지 않고 기록만 남긴 뒤 정상 종료해 재시도 대상에서 뺀다.
        // 확인은 서비스를 부르기 전에 입구에서 끝낸다. Spring 이벤트 입구와 같은 규칙을 지키기 위해서다.
        Optional<String> invalidReason = PayoutInboundEventValidator.findInvalidReason(command);
        if (invalidReason.isPresent()) {
            log.warn("[PAYOUT_WALLET_DEDUCTION_SKIPPED] 처리할 수 없는 지갑 결제 이벤트라 차감 기록을 남기지 않습니다. reason={}, command={}",
                    invalidReason.get(), command);
            return;
        }
        // DB 오류 같은 일시적 실패는 예외를 그대로 전파해 재시도 대상으로 남긴다.
        payoutWalletDeductionUseCase.saveDeduction(command);
    }
}
