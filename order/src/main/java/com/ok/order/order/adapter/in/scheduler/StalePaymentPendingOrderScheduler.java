package com.ok.order.order.adapter.in.scheduler;

import com.ok.order.order.application.port.in.CancelStalePaymentPendingOrderUseCase;
import com.ok.order.order.application.port.in.RequestPaymentExpireUseCase;
import com.ok.order.order.application.port.in.RetryStockReleaseUseCase;
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
    private final RequestPaymentExpireUseCase requestPaymentExpire;
    private final RetryStockReleaseUseCase retryStockRelease;

    // 5분마다 결제 요청 전에 멈춘 주문을 정리하고(#402), 결제 요청 뒤 결과가 없는 주문의 결제 만료를 지갑에 요청하고(#656),
    // 재고 해제를 확인하지 못한 주문의 재고를 다시 푼다(#657).
    // 정리 기준(5분, 30분)보다 자주 돌 필요는 없다. 재고 해제 재처리도 상품 배포·재시작 같은 잠깐의 장애를 넘기는 용도라 같은 주기로 둔다.
    // 시작 직후에는 돌지 않는다. 막 뜬 서버가 재시작 재발행 이벤트를 처리하기 전에 그 주문을 먼저 취소하지 않게 한다
    @Scheduled(initialDelay = 5, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void run() {
        cancelStaleOrders();
        // 결과가 올 때까지 돌 때마다 다시 요청한다. 요청이 사라져도 다음 실행이 다시 보내고, 지갑은 같은 요청에 처음 결과를 다시 보낸다
        requestPaymentExpires();
        // 정리할 주문이 없어도 재처리는 돈다. 정리와 상관없이 생성 실패·결제 실패 경로의 해제 실패도 맡는다
        retryStockReleases();
    }

    private void cancelStaleOrders() {
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

    private void requestPaymentExpires() {
        List<Long> orderIds = requestPaymentExpire.findPaymentExpireDueOrderIds();
        if (orderIds.isEmpty()) {
            return;
        }

        int requested = 0;
        int failed = 0;
        for (Long orderId : orderIds) {
            try {
                if (requestPaymentExpire.requestPaymentExpire(orderId)) {
                    requested++;
                }
            } catch (Exception e) {
                // 한 주문이 실패해도 나머지는 계속. 실패한 주문은 결제 대기로 남아 다음 실행에 다시 대상이 됨
                failed++;
                log.error("[ORDER_PAYMENT_EXPIRE_REQUEST_FAILED] 결제 만료 요청 실패 orderId={}", orderId, e);
            }
        }
        log.info("결제 만료 요청 종료 대상 주문={}, 요청={}, 실패={}", orderIds.size(), requested, failed);
    }

    private void retryStockReleases() {
        List<Long> orderIds = retryStockRelease.findStockReleasePendingOrderIds();
        for (Long orderId : orderIds) {
            try {
                retryStockRelease.retryStockRelease(orderId);
            } catch (Exception e) {
                // 재처리는 실패를 예외로 던지지 않는다. 여기 오면 예상 밖 오류라 남기고 다음 주문으로 넘어간다
                log.error("[ORDER_STOCK_RELEASE_RETRY_FAILED] 재고 해제 재처리 중 예상 밖 오류 orderId={}", orderId, e);
            }
        }
        if (!orderIds.isEmpty()) {
            log.info("재고 해제 재처리 종료 대상 주문={}", orderIds.size());
        }

        // 재처리를 마친 뒤에도 오래 해제되지 않은 주문은 사람이 확인하도록 ERROR로 남긴다
        List<Long> stuckOrderIds = retryStockRelease.findStuckStockReleaseOrderIds();
        if (!stuckOrderIds.isEmpty()) {
            log.error("[ORDER_STOCK_RELEASE_STUCK] 1시간 넘게 재고 해제를 확인하지 못한 주문이 있습니다. 확인 필요. orderIds={}",
                    stuckOrderIds);
        }
    }
}
