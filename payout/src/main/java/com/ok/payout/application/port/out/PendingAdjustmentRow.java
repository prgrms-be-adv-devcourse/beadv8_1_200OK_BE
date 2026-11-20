package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutEventType;

// 막힌 주문의 해결 대기 조정 합계 한 줄 (#723): 주문 1개 × 정산 항목 종류(판매 대금·수수료) 1개
// 조회 포트(PayoutQueryRepository)가 돌려주는 결과 모양이라 포트와 같은 application/port/out에 둠 (CandidateRow와 같은 이유)
public record PendingAdjustmentRow(
        Long orderId,
        PayoutEventType eventType,
        Long amount   // 합계 (원). 줄이 있으면 null이 아님 (조정 금액은 0이 아님)
) {
}
