package com.ok.payout.application.port.in;

import java.time.LocalDateTime;

// 해결 기록 전체 목록 조회 조건 (#723). 주문별 조회(PayoutResolutionSearchQuery)와 달리 기간이 필수
public record PayoutResolutionRequestSearchQuery(
        // 해결 시각 하한, 포함 (요청의 가장 이른 해결 기록 시각 기준)
        LocalDateTime from,
        // 해결 시각 상한, 미포함
        LocalDateTime to,
        // 해결한 관리자 회원 ID. null이면 전체 관리자
        Long adminId,
        // 페이지 번호 (0부터)
        int page,
        // 페이지 크기 (1~100, 요청 DTO에서 검증)
        int size
) {
}
