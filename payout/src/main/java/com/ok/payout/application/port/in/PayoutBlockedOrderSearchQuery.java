package com.ok.payout.application.port.in;

import java.util.List;

// 관리자 막힌 주문 목록 조회 조건 (#569, 기간 조건 없음: "지금 막힌 주문" 목록이라 기간 밖으로 밀린 오래된 주문이 바로 찾아야 할 대상)
public record PayoutBlockedOrderSearchQuery(
        // 주문 ID (#723). null이면 전체
        Long orderId,
        // 상태 이름 (#723, LOCKED·STOPPED·RESOLVED 중, 요청 DTO에서 검증). 비면 셋 다
        List<String> statuses,
        // 페이지 번호 (0부터)
        int page,
        // 페이지 크기 (1~100, 요청 DTO에서 검증)
        int size
) {

    public PayoutBlockedOrderSearchQuery {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
