package com.ok.payout.service.dto;

import java.time.LocalDateTime;

// 판매자 본인 정산 목록 조회 조건
// 관리자용(PayoutSearchQuery)과 달리 수취인(payeeId)·시스템 몫 여부(system)가 없음: 서비스가 본인·판매자 몫으로 고정함
public record PayoutSellerSearchQuery(
        // 정산 확정 시각(payout.created_at) 하한, 포함
        LocalDateTime from,
        // 정산 확정 시각 상한, 미포함 (스냅샷 기간과 같은 규칙)
        LocalDateTime to,
        // 페이지 번호 (0부터)
        int page,
        // 페이지 크기 (1~100, 요청 DTO에서 검증)
        int size
) {
}
