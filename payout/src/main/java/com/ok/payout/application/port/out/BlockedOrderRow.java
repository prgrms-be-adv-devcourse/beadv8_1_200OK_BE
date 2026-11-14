package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutBlockedStatus;
import com.ok.payout.domain.PayoutOverchargeEvidence;

import java.time.LocalDateTime;

// 관리자 막힌 주문 목록(#569)의 한 줄 = 막힌 주문 집계 표(#582) payout_blocked_order 1줄
// 원인은 페이지의 주문 ID로 따로 한 번에 읽어 서비스가 붙임
public record BlockedOrderRow(
        Long orderId,
        PayoutBlockedStatus status,
        Integer failedRunCount,       // Querydsl이 생성자를 타입으로 찾아서 엔티티 필드(int)와 맞는 감싼 타입으로 둠
        LocalDateTime lastFailedAt,
        PayoutOverchargeEvidence overchargeEvidence   // 초과 청구 "막힌 순간" 근거 (#570). 초과 청구가 원인이 아니면 null
) {
}
