package com.ok.order.order.adapter.in.scheduler;

import com.ok.order.order.application.port.in.CancelStalePaymentPendingOrderUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class StalePaymentPendingOrderScheduler {

    private final CancelStalePaymentPendingOrderUseCase cancelStalePaymentPendingOrder;

    // 5분마다 결제 대기로 오래 남은 주문을 정리한다(#402). 정리 기준(5분, 30분)보다 자주 돌 필요는 없다.
    // 시작 직후에는 돌지 않는다. 막 뜬 서버가 재시작 재발행 이벤트를 처리하기 전에 그 주문을 먼저 취소하지 않게 한다
    @Scheduled(initialDelay = 5, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void run() {
        List<Long> orderIds = cancelStalePaymentPendingOrder.findStalePaymentPendingOrderIds();
        if (orderIds.isEmpty()) {
            return;
        }

        int canceled = 0;
        int failed = 0;
        for (Long orderId : orderIds) {
            try {
                if (cancelStalePaymentPendingOrder.cancelStalePaymentPendingOrder(orderId)) {
                    canceled++;
                }
            } catch (Exception e) {
                // 한 주문이 실패해도 나머지는 계속. 실패한 주문은 결제 대기로 남아 다음 실행에 다시 대상이 됨
                failed++;
                log.error("[ORDER_STALE_CANCEL_FAILED] 결제 대기 주문 정리 실패 orderId={}", orderId, e);
            }
        }
        log.info("결제 대기 주문 정리 종료 대상 주문={}, 취소={}, 실패={}", orderIds.size(), canceled, failed);
    }
}
