package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;

import java.time.LocalDateTime;

// 막힌 주문 집계 표(#582)를 다시 판정할 때 읽는 해결 안 된 실패 기록 1줄 + 그 정산후보의 종류
// 종류는 실패 기록에 없어 정산후보와 조인해 가져옴 (같은 주문상품의 대금·수수료 원인을 구별하려고)
public record BlockedFailureRow(
        Long orderId,
        Long orderItemId,           // 배송비 후보는 null
        Long sellerId,
        PayoutEventType eventType,
        PayoutFailureReason reason,
        String detail,
        LocalDateTime failedAt      // 실패 기록 생성 시각
) {
}
