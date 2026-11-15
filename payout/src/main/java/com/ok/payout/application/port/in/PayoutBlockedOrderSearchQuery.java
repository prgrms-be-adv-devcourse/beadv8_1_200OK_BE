package com.ok.payout.application.port.in;

// 관리자 막힌 주문 목록 조회 조건 (#569, 페이징만. 기간 조건 없음: "지금 막힌 주문" 목록이라 기간 밖으로 밀린 오래된 주문이 바로 찾아야 할 대상)
public record PayoutBlockedOrderSearchQuery(
        // 페이지 번호 (0부터)
        int page,
        // 페이지 크기 (1~100, 요청 DTO에서 검증)
        int size
) {
}
