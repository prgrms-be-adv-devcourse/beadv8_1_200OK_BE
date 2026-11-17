package com.ok.order.order.adapter.in.scheduler;

import com.ok.order.order.application.port.in.OrderOutboxRelayUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주문 outbox에서 커밋 직후 보내지 못한 메시지를 정해진 주기(기본 30초)로 다시 보낸다 (#666).
 *
 * <p>주기와 한 번에 보낼 줄 수는 {@code order.outbox.relay.cron}, {@code order.outbox.relay.batch-size}로 바꾼다.
 * cron을 {@code -}로 두면 돌지 않는다(테스트는 끄고 직접 부른다).
 *
 * <p>서버가 여러 대면 같은 줄을 함께 보낼 수 있다. 커밋 직후 발송과 겹쳐도 마찬가지라, 받는 쪽은 업무 키로 중복을 거른다. 그래서 잠금을 두지 않는다.
 */
@Component
public class OrderOutboxRelayScheduler {

    private final OrderOutboxRelayUseCase orderOutboxRelay;
    private final int batchSize;

    // 설정이 없으면 0이 아니라 기본값(100)이 들어가도록 기본값을 @Value에 함께 둔다
    public OrderOutboxRelayScheduler(OrderOutboxRelayUseCase orderOutboxRelay,
                                     @Value("${order.outbox.relay.batch-size:100}") int batchSize) {
        this.orderOutboxRelay = orderOutboxRelay;
        this.batchSize = batchSize;
    }

    // 30초마다. 평소에는 커밋 직후 발송이 보내 이 작업은 PENDING 조회 한 번(인덱스)으로 끝난다.
    // 주문 생성 → 지갑 결제처럼 사용자가 결과를 기다리는 메시지도 이 기반을 쓰므로, 브로커 장애 뒤 복구가 늦지 않게 짧게 둔다.
    // cron 작업은 앞 실행이 끝난 뒤에 다음 실행 시각을 잡으므로, 브로커 장애로 오래 걸려도 자기 실행끼리 겹치지 않는다
    @Scheduled(cron = "${order.outbox.relay.cron:*/30 * * * * *}", zone = "Asia/Seoul")
    public void run() {
        orderOutboxRelay.relayPending(batchSize);
    }
}
