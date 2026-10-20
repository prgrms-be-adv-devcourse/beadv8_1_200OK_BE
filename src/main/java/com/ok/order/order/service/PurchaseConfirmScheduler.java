package com.ok.order.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseConfirmScheduler {

    private final OrderService orderService;

    // 매일 새벽 1시(한국 시간) 자동 구매확정. 정산 Job(2시)보다 먼저 끝나야 그날 정산에 포함됨
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        List<Long> orderIds = orderService.findOrderIdsToAutoConfirm();

        int confirmed = 0;
        int failed = 0;
        for (Long orderId : orderIds) {
            try {
                confirmed += orderService.autoConfirmPurchases(orderId);
            } catch (Exception e) {
                // 한 주문이 실패해도 나머지는 계속. 실패한 주문은 다음 날 다시 대상이 됨
                failed++;
                log.error("[ORDER_AUTO_CONFIRM_FAILED] 자동 구매확정 실패 orderId={}", orderId, e);
            }
        }
        log.info("자동 구매확정 종료 대상 주문={}, 확정 상품={}, 실패 주문={}", orderIds.size(), confirmed, failed);
    }
}