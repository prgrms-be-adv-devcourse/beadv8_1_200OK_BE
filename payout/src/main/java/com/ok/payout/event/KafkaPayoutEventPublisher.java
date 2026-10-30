package com.ok.payout.event;

import java.util.concurrent.ExecutionException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.ok.payout.service.dto.SellerPayoutCreatedCommand;
import com.ok.payout.service.publisher.PayoutEventPublisher;

import lombok.RequiredArgsConstructor;

/**
 * {@link PayoutEventPublisher}의 Kafka 구현체. 정산 확정을 {@link PayoutTopics#SELLER_PAYOUT_CREATED}로 보낸다.
 * 한 건마다 브로커의 저장 확인(acks=all)을 기다리고, 실패하면 예외를 던져 publishStep을 실패시킨다 (#339).
 * 발행 누락을 막는 재발행(outbox)은 아직 없다. PayoutService.publish의 TODO(#309) 참고
 */
@Component
@RequiredArgsConstructor
class KafkaPayoutEventPublisher implements PayoutEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void publishSellerPayoutCreated(SellerPayoutCreatedCommand command) {
        // 키를 payoutId로 두면 같은 정산의 메시지는 같은 파티션으로 가서 순서가 지켜지고, 지갑은 이 값으로 중복을 막는다
        String key = String.valueOf(command.payoutId());
        try {
            // 저장 확인을 기다린다. 기다리지 않으면 브로커가 거절해도 publishStep이 성공으로 끝나 실패를 놓친다
            // 최대 대기 시간은 Kafka 기본 delivery.timeout.ms(120초)
            kafkaTemplate.send(PayoutTopics.SELLER_PAYOUT_CREATED, key, SellerPayoutCreatedMessage.from(command)).get();
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw publishFailed(command, e);
        } catch (ExecutionException e) {
            throw publishFailed(command, e.getCause());
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 정산인지 남긴다
            throw publishFailed(command, e);
        }
    }

    // 배치 내부 실패라 표준 예외를 쓴다 (exception.md: 값의 출처 기준). 로그는 따로 남기지 않는다(service.md)
    // 메시지는 Step 실행 기록(EXIT_MESSAGE)에 남아 어느 정산에서 멈췄는지 찾을 수 있다
    private static IllegalStateException publishFailed(SellerPayoutCreatedCommand command, Throwable cause) {
        return new IllegalStateException("정산 지갑 이벤트 발행에 실패했습니다. payoutId=" + command.payoutId(), cause);
    }
}
