package com.ok.wallet.adapter.in.event;

import com.ok.wallet.adapter.in.event.dto.SellerPayoutCreatedMessage;
import com.ok.wallet.application.port.in.SellerWalletUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 정산이 Kafka로 보내는 정산금 확정을 받아 판매자 지갑에 입금한다.
 * 같은 정산이 여러 번 와도 서비스가 payoutId로 한 번만 입금한다.
 */
@Component
@RequiredArgsConstructor
public class PayoutKafkaListener {
    private final SellerWalletUseCase sellerWalletUseCase;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = WalletKafkaTopics.SELLER_PAYOUT_CREATED,
            groupId = WalletKafkaTopics.CONSUMER_GROUP,
            containerFactory = WalletKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.wallet.adapter.in.event.dto.SellerPayoutCreatedMessage")
    void on(SellerPayoutCreatedMessage message) {
        // 값이 틀린 메시지는 다시 해도 같으므로 IllegalArgumentException으로 알린다. 지갑 오류 처리기가 다시 시도하지 않고 원문과 함께 실패 기록에 남긴다.
        // 확인은 트랜잭션이 걸린 서비스를 부르기 전에 끝낸다
        validate(message);
        // DB 오류 같은 일시적 실패는 예외를 그대로 전파한다. 지갑 오류 처리기가 다시 시도하고, 끝내 실패하면 기록한다
        sellerWalletUseCase.depositPayout(message.payeeId(), message.payoutId(), message.amount());
    }

    // 정산 쪽 약속: 모든 값이 있고 amount는 1 이상이다
    private static void validate(SellerPayoutCreatedMessage message) {
        if (message.payoutId() == null) {
            throw invalid("payoutId 없음");
        }
        if (message.payeeId() == null) {
            throw invalid("payeeId 없음");
        }
        if (message.amount() == null) {
            throw invalid("amount 없음");
        }
        if (message.amount() <= 0) {
            throw invalid("amount가 0 이하");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("처리할 수 없는 정산 메시지입니다. reason=" + reason);
    }
}
