package com.ok.payout.application.port.in;

// 해결 기록 조회 조건 (#571, 페이징만. 대상 주문 ID는 URL 경로로 따로 받음)
public record PayoutResolutionSearchQuery(
        // 페이지 번호 (0부터)
        int page,
        // 페이지 크기 (1~100, 요청 DTO에서 검증)
        int size
) {
}
