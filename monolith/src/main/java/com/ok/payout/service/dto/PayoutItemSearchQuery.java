package com.ok.payout.service.dto;

// 정산 항목 조회 조건 (페이징만. 대상 정산 ID는 URL 경로로 따로 받음)
public record PayoutItemSearchQuery(
        // 페이지 번호 (0부터)
        int page,
        // 페이지 크기 (1~100, 요청 DTO에서 검증)
        int size
) {
}
