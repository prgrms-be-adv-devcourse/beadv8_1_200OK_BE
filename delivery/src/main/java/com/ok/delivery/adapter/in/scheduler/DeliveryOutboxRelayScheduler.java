package com.ok.delivery.adapter.in.scheduler;

import com.ok.delivery.application.port.in.DeliveryOutboxRelayUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code DeliveryOutboxSendListener}가 커밋 직후 보내지 못한 배송 outbox 줄(발송 실패, 커밋 직후 서버 종료)을 정해진 주기(기본 30초)로 보낸다 (#644).
 * 주기와 한 번에 보낼 줄 수, 대상이 될 최소 경과 시간은 {@code delivery.outbox.relay.cron}, {@code batch-size}, {@code min-age}로 바꾼다.
 * cron을 {@code -}로 두면 돌지 않는다(테스트는 끄고 직접 부른다).
 * 서버가 여러 대면 같은 줄을 함께 보낼 수 있다. 받는 쪽(주문)이 중복을 무시하므로(#603) 잠금을 두지 않는다.
 */
@Component
public class DeliveryOutboxRelayScheduler {

    private final DeliveryOutboxRelayUseCase deliveryOutboxRelay;
    private final int batchSize;
    private final Duration minAge;

    // 설정이 없으면 0이 아니라 기본값이 들어가도록 기본값을 @Value에 함께 둔다.
    // min-age 기본 10초는 즉시 발송의 브로커 확인 대기 상한(KafkaDeliveryMessageSender.SEND_TIMEOUT)과 같아, 즉시 발송이 끝난 줄만 고른다
    public DeliveryOutboxRelayScheduler(DeliveryOutboxRelayUseCase deliveryOutboxRelay,
                                        @Value("${delivery.outbox.relay.batch-size:100}") int batchSize,
                                        @Value("${delivery.outbox.relay.min-age:10s}") Duration minAge) {
        this.deliveryOutboxRelay = deliveryOutboxRelay;
        this.batchSize = batchSize;
        this.minAge = minAge;
    }

    // 이 주기는 즉시 발송이 실패한 뒤의 복구에만 걸린다. 주문은 배송 상태를 화면에 보여 줄 뿐이라 그때는 수십 초 늦어도 된다.
    // cron 작업은 앞 실행이 끝난 뒤에 다음 실행 시각을 잡으므로, 브로커 장애로 오래 걸려도 자기 실행끼리 겹치지 않는다
    @Scheduled(cron = "${delivery.outbox.relay.cron:*/30 * * * * *}", zone = "Asia/Seoul")
    public void run() {
        deliveryOutboxRelay.relayPending(batchSize, minAge);
    }

}
