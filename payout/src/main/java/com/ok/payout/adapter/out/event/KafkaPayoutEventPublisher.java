package com.ok.payout.adapter.out.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
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
 * 지갑이 .v1로 옮길 때까지 같은 메시지를 옛 이름 토픽에도 함께 보낸다 (#653).
 * 한 건마다 브로커의 저장 확인(acks=all)을 기다리고, 실패하면 어느 메시지·어느 토픽인지 담은 예외를 던진다 (#339).
 */
@Component
@RequiredArgsConstructor
class KafkaPayoutEventPublisher implements PayoutEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publishSellerPayoutCreated(SellerPayoutCreated created) {
        // 키를 payoutId로 두면 같은 정산의 메시지는 같은 파티션으로 가서 순서가 지켜지고, 지갑은 이 값으로 중복을 막는다
        String key = String.valueOf(created.payoutId());
        send(List.of(PayoutTopics.SELLER_PAYOUT_CREATED, PayoutTopics.SELLER_PAYOUT_CREATED_OLD),
                key, SellerPayoutCreatedMessage.from(created),
                "정산 Kafka 발행에 실패했습니다. payoutId=" + created.payoutId());
    }

    @Override
    public void publishDepositCheckRequested(PayoutDepositCheckRequested requested) {
        // 키는 묶음 번호(checkId, 지갑과 합의). 묶음 하나에 답 하나라 같은 묶음끼리 순서를 맞출 일은 없고, 파티션에 고르게 퍼지게 함
        String key = String.valueOf(requested.checkId());
        send(List.of(PayoutTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED, PayoutTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED_OLD),
                key, PayoutDepositCheckRequestedMessage.from(requested),
                "정산 대사 질문 Kafka 발행에 실패했습니다. checkId=" + requested.checkId());
    }

    // 한 토픽이라도 실패하면 예외를 던진다. 부른 쪽이 다시 보낼 때 두 토픽에 모두 다시 나가지만,
    // 지갑은 정산금 확정을 PAYOUT:{payoutId}로 한 번만 입금하고, 대사 질문은 원장을 읽기만 해서 두 번 받아도 괜찮다 (#653)
    private void send(List<String> topics, String key, Object message, String failureMessage) {
        // 모든 토픽에 먼저 보내 놓고 결과를 기다린다. 하나씩 보내고 기다리면 브로커가 느릴 때 기다리는 시간이 토픽 수만큼 늘어난다
        List<CompletableFuture<SendResult<String, Object>>> results = new ArrayList<>();
        for (String topic : topics) {
            results.add(sendNow(topic, key, message, failureMessage));
        }
        for (int i = 0; i < topics.size(); i++) {
            await(results.get(i), failureMessage + ", topic=" + topics.get(i));
        }
    }

    private CompletableFuture<SendResult<String, Object>> sendNow(
            String topic, String key, Object message, String failureMessage) {
        try {
            return kafkaTemplate.send(topic, key, message);
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 메시지·토픽인지 남긴다
            // 배치 내부 실패라 표준 예외를 쓴다 (exception.md: 값의 출처 기준). 로그는 따로 남기지 않는다(service.md)
            throw new IllegalStateException(failureMessage + ", topic=" + topic, e);
        }
    }

    private void await(CompletableFuture<SendResult<String, Object>> result, String failureMessage) {
        try {
            // 저장 확인을 기다린다. 기다리지 않으면 브로커가 거절해도 "보냄"으로 처리돼 실패를 놓친다
            // 최대 대기 시간은 Kafka 기본 delivery.timeout.ms(120초)
            result.get();
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(failureMessage, e.getCause());
        } catch (RuntimeException e) {
            // 결과가 취소된 경우(CancellationException)도 어느 메시지·토픽인지 남긴다
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
