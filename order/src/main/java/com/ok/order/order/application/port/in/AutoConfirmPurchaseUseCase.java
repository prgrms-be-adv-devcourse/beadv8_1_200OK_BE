package com.ok.order.order.application.port.in;

import java.util.List;

/**
 * 배송 완료 뒤 기간이 지난 주문 상품을 자동으로 구매확정한다. 스케줄러가 쓴다.
 */
public interface AutoConfirmPurchaseUseCase {

    /**
     * 자동 구매확정 대상 주문 ID를 조회한다.
     */
    List<Long> findOrderIdsToAutoConfirm();

    /**
     * 주문 하나의 대상 상품을 구매확정한다. 주문마다 트랜잭션을 나눠, 한 주문이 실패해도 다른 주문은 진행한다.
     *
     * @return 확정한 주문 상품 수
     */
    int autoConfirmPurchases(Long orderId);
}
