package com.ok.payout.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

// 관리자 막힌 주문 목록 1줄 = 막힌 주문 1개 (#569)
public record PayoutBlockedOrderResult(
        Long orderId,
        // LOCKED 잠김, STOPPED 멈춤, RESOLVED 해결됨(다음 정산 실행 끝에 정리)
        String status,
        // 성공한 실행에서 해결 안 된 실패가 남은 실행 수 (멈춤 판정용. 잠김은 이 값과 상관없음)
        int failedRunCount,
        LocalDateTime lastFailedAt,
        // 원인에 나온 판매자 ID (중복 없음, 작은 번호부터)
        List<Long> sellerIds,
        // 원인에 나온 사유·범주 이름 (중복 없음, 원인 순서대로)
        List<String> reasons,
        List<String> categories,
        List<PayoutBlockedOrderCauseResult> causes
) {
}
