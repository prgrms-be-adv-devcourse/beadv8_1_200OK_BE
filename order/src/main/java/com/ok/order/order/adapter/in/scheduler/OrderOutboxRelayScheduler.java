package com.ok.order.order.adapter.in.scheduler;

import com.ok.order.order.application.port.in.OrderOutboxRelayUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 주문 outbox에서 커밋 직후 보내지 못한 메시지를 정해진 주기(기본 5초)로 다시 보낸다 (#666).
 *
 * <p>주기와 한 번에 보낼 줄 수, 대상이 될 최소 경과 시간은 {@code order.outbox.relay.cron}, {@code batch-size}, {@code min-age}로 바꾼다.
 * cron을 {@code -}로 두면 돌지 않는다(테스트는 끄고 직접 부른다).
 *
 * <p>{@code min-age}(기본 10초)는 실패한 줄을 포기하는 기준이 아니라, 커밋 직후 발송이 아직 처리 중인 줄을 재발송이 집지 않게 하는 유예 시간이다.
 * 없으면 커밋 직후 발송이 대기열에서 밀린 사이 재발송이 같은 줄을 함께 보내 받는 쪽이 중복을 받는다(#794, 300 rps 부하테스트에서 584건).
 * 배송 outbox와 같은 키 이름을 쓴다.
 *
 * <p>서버가 여러 대면 같은 줄을 함께 보낼 수 있다. 커밋 직후 발송과 겹쳐도 마찬가지라, 받는 쪽은 업무 키로 중복을 거른다. 그래서 잠금을 두지 않는다.
 */
@Component
public class OrderOutboxRelayScheduler {

    private final OrderOutboxRelayUseCase orderOutboxRelay;
    private final int batchSize;
    private final Duration minAge;

    // 설정이 없으면 0이 아니라 기본값이 들어가도록 기본값을 @Value에 함께 둔다.
    // min-age 기본 10초는 커밋 직후 발송의 저장 확인 대기 상한(KafkaOrderMessageSender.SEND_TIMEOUT)과 같아, 커밋 직후 발송이 끝났을 줄만 고른다
    public OrderOutboxRelayScheduler(OrderOutboxRelayUseCase orderOutboxRelay,
                                     @Value("${order.outbox.relay.batch-size:300}") int batchSize,
                                     @Value("${order.outbox.relay.min-age:10s}") Duration minAge) {
        this.orderOutboxRelay = orderOutboxRelay;
        this.batchSize = batchSize;
        this.minAge = minAge;
    }

    // 5초마다 300줄씩. 평소에는 커밋 직후 발송이 보내 이 작업은 PENDING 조회 한 번(인덱스)으로 끝난다.
    // 주문 생성 → 지갑 결제처럼 사용자가 결과를 기다리는 메시지도 이 기반을 쓰므로, 브로커 장애 뒤 복구가 늦지 않게 짧게 둔다.
    // 재발송은 한 스레드가 차례로 보내 초당 약 120줄이라(배송 측정, #793) 300줄은 약 2.5초로 주기 안에 끝난다. 주기를 넘기면 cron이 회차를 건너뛴다.
    // 예전 값(30초에 100줄)은 몰린 뒤 쌓인 줄을 비우는 데 몇 시간이 걸렸다(docs/decisions/order/794-order-outbox-send-defaults.md)
    // cron 작업은 앞 실행이 끝난 뒤에 다음 실행 시각을 잡으므로, 브로커 장애로 오래 걸려도 자기 실행끼리 겹치지 않는다
    @Scheduled(cron = "${order.outbox.relay.cron:*/5 * * * * *}", zone = "Asia/Seoul")
    public void run() {
        orderOutboxRelay.relayPending(batchSize, minAge);
    }
}
