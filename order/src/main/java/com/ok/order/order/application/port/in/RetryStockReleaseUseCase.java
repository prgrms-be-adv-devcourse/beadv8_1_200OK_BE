package com.ok.order.order.application.port.in;

import java.util.List;

/**
 * 재고 해제를 확인하지 못한 취소·결제 실패 주문의 재고를 다시 푼다(#657). 정리 스케줄러가 쓴다.
 * 상품의 해제는 여러 번 불러도 결과가 같아(#605) 확인될 때까지 다시 불러도 안전하다.
 */
public interface RetryStockReleaseUseCase {

    /**
     * 재고 해제를 다시 부를 주문 ID를 오래된 주문부터 조회한다. 방금 상태가 바뀐 주문은 원래 경로가 해제 중일 수 있어 뺀다.
     */
    List<Long> findStockReleasePendingOrderIds();

    /**
     * 오래 지나도 재고 해제를 확인하지 못한 주문 ID를 조회한다. 사람이 확인해야 하는 주문이다.
     */
    List<Long> findStuckStockReleaseOrderIds();

    /**
     * 주문 하나의 재고를 다시 풀고, 성공하면 해제 확인을 기록한다. 실패해도 예외를 던지지 않고 다음 재처리에 맡긴다.
     */
    void retryStockRelease(Long orderId);
}
