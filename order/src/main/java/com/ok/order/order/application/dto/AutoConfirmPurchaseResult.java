package com.ok.order.order.application.dto;

import java.util.List;

/**
 * 주문 하나의 자동 구매확정 결과.
 *
 * @param confirmedCount 확정한 주문 상품 수
 * @param outboxIds      정산에 보낼 메시지를 남긴 outbox 줄 ID. 모두 환불된 상품은 보내지 않아 확정 수보다 적을 수 있다
 */
public record AutoConfirmPurchaseResult(int confirmedCount, List<Long> outboxIds) {
}
