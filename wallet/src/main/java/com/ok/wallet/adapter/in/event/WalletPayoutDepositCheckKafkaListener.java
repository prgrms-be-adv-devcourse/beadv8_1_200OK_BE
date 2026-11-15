package com.ok.wallet.adapter.in.event;

import com.ok.wallet.adapter.in.event.dto.PayoutDepositCheckRequestedMessage;
import com.ok.wallet.application.dto.PayoutDepositCheckCommand;
import com.ok.wallet.application.port.in.PayoutDepositCheckUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 정산이 Kafka로 묻는 정산금 입금 확인 묶음을 받아 판매자 원장을 읽은 답을 보낸다.
 * 읽기만 하므로 같은 묶음이 여러 번 와도 올 때마다 같은 답을 보내고 잔액은 바뀌지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletPayoutDepositCheckKafkaListener {
    private final PayoutDepositCheckUseCase payoutDepositCheckUseCase;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = WalletKafkaTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED,
            groupId = WalletKafkaTopics.CONSUMER_GROUP,
            containerFactory = WalletKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.wallet.adapter.in.event.dto.PayoutDepositCheckRequestedMessage")
    void on(PayoutDepositCheckRequestedMessage message) {
        // 묶음 자체가 틀리면 어디에 답할지 모르므로 IllegalArgumentException으로 알린다. 지갑 오류 처리기가 다시 시도하지 않고 원문과 함께 실패 기록에 남긴다
        validate(message);
        // 보내기 실패는 예외를 그대로 전파한다. 지갑 오류 처리기가 다시 시도하고, 그때 원장을 다시 읽어 같은 답을 보낸다
        payoutDepositCheckUseCase.check(new PayoutDepositCheckCommand(message.checkId(), readablePayoutIds(message)));
    }

    // 빈 묶음은 정산이 보내지 않기로 한 모양이라 틀린 묶음으로 본다
    private static void validate(PayoutDepositCheckRequestedMessage message) {
        if (message.checkId() == null) {
            throw invalid("checkId 없음");
        }
        if (message.items() == null || message.items().isEmpty()) {
            throw invalid("items 없음");
        }
    }

    // 항목 하나가 틀렸다고 묶음 전체를 실패 기록에 넣으면 나머지 정산까지 답을 못 받는다.
    // 읽을 수 없는 항목만 빼면 정산은 답이 없는 정산을 다음 날 다시 묻는다
    private static List<Long> readablePayoutIds(PayoutDepositCheckRequestedMessage message) {
        List<Long> payoutIds = new ArrayList<>(message.items().size());
        for (PayoutDepositCheckRequestedMessage.Item item : message.items()) {
            if (item == null || item.payoutId() == null) {
                log.warn("정산 ID가 없는 입금 확인 항목을 답에서 뺐습니다. checkId={}, payeeId={}, amount={}",
                        message.checkId(), item == null ? null : item.payeeId(), item == null ? null : item.amount());
                continue;
            }
            payoutIds.add(item.payoutId());
        }
        return payoutIds;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("처리할 수 없는 정산 입금 확인 요청입니다. reason=" + reason);
    }
}
