package com.ok.wallet.adapter.out.event;

import com.ok.wallet.adapter.out.event.dto.PayoutDepositCheckedMessage;
import com.ok.wallet.application.dto.PayoutDepositCheckResult;
import com.ok.wallet.application.port.out.PayoutDepositCheckResultPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link PayoutDepositCheckResultPublisher}의 Kafka 구현체. 묶음 하나의 답을 메시지 하나로 보내고 저장 확인까지 기다린다.
 * 답은 원장을 읽은 사실이라 두 번 가도 정산의 판정이 달라지지 않는다.
 */
@Component
@RequiredArgsConstructor
class KafkaPayoutDepositCheckResultPublisher implements PayoutDepositCheckResultPublisher {
    private final WalletKafkaSender walletKafkaSender;

    // 키를 checkId로 두면 같은 묶음의 답이 다시 가도 같은 파티션에서 보낸 순서대로 받는다
    @Override
    public void publish(PayoutDepositCheckResult result) {
        PayoutDepositCheckedMessage message = new PayoutDepositCheckedMessage(result.checkId(), result.items().stream()
                .map(item -> new PayoutDepositCheckedMessage.Item(
                        item.payoutId(), item.found(), item.payeeId(), item.amount(), item.depositedAt()))
                .toList());
        walletKafkaSender.send(WalletOutboundTopics.PAYOUT_DEPOSIT_CHECKED, String.valueOf(result.checkId()), message,
                "정산 입금 확인 답을 보내지 못했습니다. checkId=" + result.checkId());
    }
}
