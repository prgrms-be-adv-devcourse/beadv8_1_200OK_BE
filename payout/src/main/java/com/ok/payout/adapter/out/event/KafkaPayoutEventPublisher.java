package com.ok.payout.adapter.out.event;

import java.util.concurrent.ExecutionException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.ok.payout.adapter.out.event.dto.PayoutDepositCheckRequestedMessage;
import com.ok.payout.adapter.out.event.dto.SellerPayoutCreatedMessage;
import com.ok.payout.application.port.out.PayoutDepositCheckRequested;
import com.ok.payout.application.port.out.SellerPayoutCreated;
import com.ok.payout.application.port.out.PayoutEventPublisher;
import com.ok.payout.config.PayoutTopics;

import lombok.RequiredArgsConstructor;

/**
 * {@link PayoutEventPublisher}의 Kafka 구현체. 정산 확정을 {@link PayoutTopics#SELLER_PAYOUT_CREATED}로,
 * 대사 질문 묶음을 {@link PayoutTopics#PAYOUT_DEPOSIT_CHECK_REQUESTED}로 보낸다.
 * 한 건마다 브로커의 저장 확인(acks=all)을 기다리고, 실패하면 어느 메시지인지 담은 예외를 던진다 (#339).
 */
@Component
@RequiredArgsConstructor
class KafkaPayoutEventPublisher implements PayoutEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publishSellerPayoutCreated(SellerPayoutCreated created) {
        // 키를 payoutId로 두면 같은 정산의 메시지는 같은 파티션으로 가서 순서가 지켜지고, 지갑은 이 값으로 중복을 막는다
        String key = String.valueOf(created.payoutId());
        send(PayoutTopics.SELLER_PAYOUT_CREATED, key, SellerPayoutCreatedMessage.from(created),
                "정산 Kafka 발행에 실패했습니다. payoutId=" + created.payoutId());
    }

    @Override
    public void publishDepositCheckRequested(PayoutDepositCheckRequested requested) {
        // 키는 묶음 번호(checkId, 지갑과 합의). 묶음 하나에 답 하나라 같은 묶음끼리 순서를 맞출 일은 없고, 파티션에 고르게 퍼지게 함
        String key = String.valueOf(requested.checkId());
        send(PayoutTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED, key, PayoutDepositCheckRequestedMessage.from(requested),
                "정산 대사 질문 Kafka 발행에 실패했습니다. checkId=" + requested.checkId());
    }

    private void send(String topic, String key, Object message, String failureMessage) {
        try {
            // 저장 확인을 기다린다. 기다리지 않으면 브로커가 거절해도 "보냄"으로 처리돼 실패를 놓친다
            // 최대 대기 시간은 Kafka 기본 delivery.timeout.ms(120초)
            kafkaTemplate.send(topic, key, message).get();
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(failureMessage, e.getCause());
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 메시지인지 남긴다
            // 배치 내부 실패라 표준 예외를 쓴다 (exception.md: 값의 출처 기준). 로그는 따로 남기지 않는다(service.md)
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
