package com.ok.payout.adapter.out.event;

import java.util.concurrent.ExecutionException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.payout.adapter.out.event.dto.SellerPayoutCreatedMessage;
import com.ok.payout.application.port.out.SellerPayoutCreated;
import com.ok.payout.application.port.out.PayoutEventPublisher;
import com.ok.payout.config.PayoutTopics;

import lombok.RequiredArgsConstructor;

/**
 * {@link PayoutEventPublisher}의 Kafka 구현체. 정산 확정을 {@link PayoutTopics#SELLER_PAYOUT_CREATED}로 보낸다.
 * 한 건마다 브로커의 저장 확인(acks=all)을 기다리고, 실패하면 예외를 던져 publishStep을 실패시킨다 (#339).
 * 지갑이 Kafka로 받기 전까지는 같은 정산을 Spring 이벤트로도 발행한다(옮기는 동안의 이중 발행).
 * 발행 누락을 막는 재발행(outbox)은 아직 없다. PayoutService.publish의 TODO(#309) 참고
 */
@Component
@RequiredArgsConstructor
class KafkaPayoutEventPublisher implements PayoutEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // TODO(#339): 지갑 리스너가 Kafka로 바뀌면 이 필드와 아래 Spring 이벤트 발행을 지운다
    //   지갑은 지금 Spring 이벤트(common.event.SellerPayoutCreatedEvent)로 받으므로, 입금이 끊기지 않게 함께 보낸다
    //   둘 다 받는 순간이 있어도 지갑이 payoutId로 중복 입금을 막는다
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishSellerPayoutCreated(SellerPayoutCreated created) {
        // 키를 payoutId로 두면 같은 정산의 메시지는 같은 파티션으로 가서 순서가 지켜지고, 지갑은 이 값으로 중복을 막는다
        String key = String.valueOf(created.payoutId());
        try {
            // 저장 확인을 기다린다. 기다리지 않으면 브로커가 거절해도 publishStep이 성공으로 끝나 실패를 놓친다
            // 최대 대기 시간은 Kafka 기본 delivery.timeout.ms(120초)
            kafkaTemplate.send(PayoutTopics.SELLER_PAYOUT_CREATED, key, SellerPayoutCreatedMessage.from(created)).get();
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw publishFailed(created, e);
        } catch (ExecutionException e) {
            throw publishFailed(created, e.getCause());
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 정산인지 남긴다
            throw publishFailed(created, e);
        }

        // Kafka 저장 확인 뒤에 보낸다. 이 정산이 Kafka에서 실패하면 Spring 이벤트도 보내지 않는다
        // 다만 여러 건 중 중간에서 실패하면 두 경로가 갈라진다 (옮기는 동안의 한계)
        //   예) 100건 중 37번째 실패 → Kafka에는 1~36이 이미 저장됨(되돌릴 수 없음)
        //       Spring 이벤트는 지갑 리스너가 커밋 후에 받으므로(@ApplicationModuleListener) publish 롤백과 함께 1~36 모두 전달되지 않음
        //   어느 쪽이든 확정된 정산이 지갑에 안 가는 발행 누락이며, outbox(#309)에서 함께 해결한다
        applicationEventPublisher.publishEvent(new SellerPayoutCreatedEvent(
                created.payoutId(), created.payeeId(), created.amount(), created.createdAt()));
    }

    // 배치 내부 실패라 표준 예외를 쓴다 (exception.md: 값의 출처 기준). 로그는 따로 남기지 않는다(service.md)
    // 메시지는 Step 실행 기록(EXIT_MESSAGE)에 남아 어느 정산에서 멈췄는지 찾을 수 있다
    private static IllegalStateException publishFailed(SellerPayoutCreated created, Throwable cause) {
        return new IllegalStateException("정산 지갑 이벤트 발행에 실패했습니다. payoutId=" + created.payoutId(), cause);
    }
}
