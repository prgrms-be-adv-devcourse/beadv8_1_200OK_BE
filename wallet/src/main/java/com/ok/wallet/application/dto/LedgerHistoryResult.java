package com.ok.wallet.application.dto;

import java.time.LocalDateTime;

// 이용 내역 한 줄. orderId·orderSummary는 결제·환불 반환·환불 되돌리기 내역에만 있고(못 찾으면 null),
// 금액은 항상 양수라 증감은 type으로 구분한다.
public record LedgerHistoryResult(
        LocalDateTime occurredAt, String type, Long orderId, String orderSummary, long amount, long balanceAfter) {
}
