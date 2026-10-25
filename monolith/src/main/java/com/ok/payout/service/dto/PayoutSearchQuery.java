package com.ok.payout.service.dto;

import java.time.LocalDateTime;

// 관리자 정산 목록 조회 조건. 값이 null인 조건은 조회에 걸지 않음. 판매자용은 PayoutSellerSearchQuery
public record PayoutSearchQuery(
        // 수취인(판매자) 회원 ID. 생략하면 전체
        Long payeeId,
        // true: 시스템 몫만, false: 판매자 몫만, null: 둘 다
        Boolean system,
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
