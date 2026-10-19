package com.ok.wallet.service.dto;

import java.time.LocalDateTime;

// 이용 내역 한 줄. orderId는 결제 내역일 때만 있고, 금액은 항상 양수라 증감은 type으로 구분한다.
public record LedgerHistoryResult(
        LocalDateTime occurredAt, String type, Long orderId, long amount, long balanceAfter) {
}
