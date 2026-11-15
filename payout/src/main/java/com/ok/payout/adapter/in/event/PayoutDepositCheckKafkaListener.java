package com.ok.payout.adapter.in.event;

import com.ok.payout.adapter.in.event.dto.PayoutDepositCheckedMessage;
import com.ok.payout.application.port.in.PayoutDepositCheckAnswerCommand;
import com.ok.payout.application.port.in.PayoutDepositCheckUseCase;
import com.ok.payout.config.PayoutKafkaConsumerConfig;
import com.ok.payout.config.PayoutTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 지갑이 보내는 대사 답 묶음을 Kafka로 받는 입구다 (#619, 지갑 #613).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutDepositCheckKafkaListener {

    private final PayoutDepositCheckUseCase payoutDepositCheckUseCase;

    // 받는 타입은 정산 DTO. 메시지에 보내는 쪽 클래스 이름이 없어서(타입 헤더 없음) 리스너마다 읽을 타입을 정한다 (PayoutWalletKafkaListener와 같음)
    // 지갑이 아직 답을 보내지 않아도(#613 전) 미리 열어 둔다
    @KafkaListener(
            topics = PayoutTopics.WALLET_PAYOUT_DEPOSIT_CHECKED,
            groupId = PayoutTopics.CONSUMER_GROUP,
            containerFactory = PayoutKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.payout.adapter.in.event.dto.PayoutDepositCheckedMessage")
    void onKafka(PayoutDepositCheckedMessage message) {
        // 묶음 자체가 틀리면(번호·항목 없음) 다시 받아도 같으므로 IllegalArgumentException으로 알린다
        // 정산 오류 처리기(PayoutKafkaConsumerConfig)는 이 예외를 다시 시도하지 않고 ERROR 로그를 남긴 뒤 다음 메시지로 넘어간다 (PayoutWalletKafkaListener와 같은 규칙)
        // 답을 버려도 대사 줄은 확인 대기로 남아 다음 날 다시 물으므로 빠지지 않는다
        if (message.checkId() == null || message.items() == null) {
            throw new IllegalArgumentException("[PAYOUT_DEPOSIT_CHECK_SKIPPED] 처리할 수 없는 대사 답 묶음입니다. checkId="
                    + message.checkId() + ", items=" + (message.items() == null ? "null" : message.items().size() + "건"));
        }
        // DB 오류 같은 일시적 실패는 예외를 그대로 전파한다. 정산 오류 처리기가 간격을 늘려 가며(약 31초) 다시 시도한다.
        // 같은 묶음을 다시 처리해도 대사 줄의 "그 답을 처리했는지" 표시로 두 번 반영되지 않는다
        payoutDepositCheckUseCase.handleAnswer(toCommand(message));
    }

    // 묶음 안에서 읽을 수 없는 항목(정산 ID·있음 여부 없음)만 빼고 나머지는 반영한다
    // 지갑도 읽을 수 없는 항목은 답에서 빼기로 했으니(합의 4-3절) 여기까지 오면 지갑 쪽 버그라 남긴다. 그 정산은 다음 날 다시 묻는다
    private static PayoutDepositCheckAnswerCommand toCommand(PayoutDepositCheckedMessage message) {
        List<PayoutDepositCheckAnswerCommand.Item> items = new ArrayList<>();
        for (PayoutDepositCheckedMessage.Item item : message.items()) {
            if (item == null || item.payoutId() == null || item.found() == null) {
                log.warn("[PAYOUT_DEPOSIT_CHECK_SKIPPED] 읽을 수 없는 대사 답 항목이라 건너뜀. checkId={}, item={}", message.checkId(), item);
                continue;
            }
            items.add(new PayoutDepositCheckAnswerCommand.Item(
                    item.payoutId(), item.found(), item.payeeId(), item.amount(), item.depositedAt()));
        }
        return new PayoutDepositCheckAnswerCommand(message.checkId(), items);
    }
}
